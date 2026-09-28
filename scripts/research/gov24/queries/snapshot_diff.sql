-- 두 스냅샷 비교 (prev: 이전, cur: 이후). --compare를 줬을 때만 실행한다.
-- 조회수는 수시로 바뀌므로 내용 비교에서 뺀다. 수정일시 자체의 변화는 따로 센다.

-- name: setup
-- 두 레코드에서 값이 다른 필드 목록. 키 순서와 관계없이 필드 단위로 비교한다.
CREATE TEMP MACRO changed_fields(a, b) AS
    list_sort(list_filter(list_distinct(json_keys(a) || json_keys(b)), f -> (a -> f) IS DISTINCT FROM (b -> f)));
CREATE TEMP TABLE diff AS
WITH pairs AS (
    SELECT 'serviceList' AS 경로, coalesce((c.rec ->> '서비스ID'), (p.rec ->> '서비스ID')) AS 서비스ID, p.rec AS prev_rec, c.rec AS cur_rec
    FROM cur.service_list_raw c FULL OUTER JOIN prev.service_list_raw p ON (c.rec ->> '서비스ID') = (p.rec ->> '서비스ID')
    UNION ALL
    SELECT 'serviceDetail', coalesce((c.rec ->> '서비스ID'), (p.rec ->> '서비스ID')), p.rec, c.rec
    FROM cur.service_detail_raw c FULL OUTER JOIN prev.service_detail_raw p ON (c.rec ->> '서비스ID') = (p.rec ->> '서비스ID')
    UNION ALL
    SELECT 'supportConditions', coalesce((c.rec ->> '서비스ID'), (p.rec ->> '서비스ID')), p.rec, c.rec
    FROM cur.support_conditions_raw c FULL OUTER JOIN prev.support_conditions_raw p ON (c.rec ->> '서비스ID') = (p.rec ->> '서비스ID')
)
SELECT 경로, 서비스ID, prev_rec, cur_rec, 바뀐_필드,
       CASE
           WHEN prev_rec IS NULL THEN '추가'
           WHEN cur_rec IS NULL THEN '사라짐'
           WHEN len(list_filter(바뀐_필드, f -> f NOT IN ('조회수', '수정일시'))) > 0 THEN '내용 변경'
           WHEN list_contains(바뀐_필드, '수정일시') THEN '수정일시만 변경'
           WHEN list_contains(바뀐_필드, '조회수') THEN '조회수만 변경'
           ELSE '같음'
       END AS 상태
FROM (SELECT *, CASE WHEN prev_rec IS NOT NULL AND cur_rec IS NOT NULL THEN changed_fields(prev_rec, cur_rec) END AS 바뀐_필드 FROM pairs);

-- name: 비교한 스냅샷
SELECT (SELECT snapshot_id FROM prev.snapshot) AS 이전,
       (SELECT snapshot_id FROM cur.snapshot) AS 이후,
       date_diff('day', (SELECT snapshot_date FROM prev.snapshot), (SELECT snapshot_date FROM cur.snapshot)) AS 간격_일;

-- name: 경로별 서비스ID 증감과 변경
SELECT 경로, 상태, count(*) AS 서비스_수
FROM diff
GROUP BY ALL
ORDER BY 경로, 상태;

-- name: 내용이 바뀐 필드별 건수
SELECT 경로, 필드, count(*) AS 변경_건수
FROM (SELECT 경로, unnest(바뀐_필드) AS 필드 FROM diff WHERE 상태 = '내용 변경')
WHERE 필드 NOT IN ('조회수', '수정일시')
GROUP BY ALL
ORDER BY 경로, 변경_건수 DESC, 필드;

-- name: 추가·사라진 서비스 예시 (각 5건)
SELECT 상태, 서비스ID,
       coalesce(cur_rec ->> '서비스명', prev_rec ->> '서비스명') AS 서비스명,
       coalesce(cur_rec ->> '등록일시', prev_rec ->> '등록일시') AS 등록일시,
       coalesce(cur_rec ->> '수정일시', prev_rec ->> '수정일시') AS 수정일시
FROM diff
WHERE 경로 = 'serviceList' AND 상태 IN ('추가', '사라짐')
QUALIFY row_number() OVER (PARTITION BY 상태 ORDER BY md5(서비스ID || ':diff-example')) <= 5
ORDER BY 상태, 서비스ID;

-- name: 목록 수정일시 변화와 세 경로 내용 변화의 관계 (공통 서비스)
WITH per_service AS (
    SELECT 서비스ID,
           bool_or(상태 = '내용 변경') AS 내용_변경,
           bool_or(경로 = 'serviceList' AND (prev_rec ->> '수정일시') IS DISTINCT FROM (cur_rec ->> '수정일시')) AS 목록_수정일시_변경
    FROM diff
    WHERE 상태 NOT IN ('추가', '사라짐')
    GROUP BY 서비스ID
)
SELECT 목록_수정일시_변경, 내용_변경, count(*) AS 서비스_수
FROM per_service
GROUP BY ALL
ORDER BY 목록_수정일시_변경, 내용_변경;

-- name: 수정일시 필터(cond[수정일시::GTE]) 조회와 목록 비교
WITH f AS (SELECT DISTINCT rec ->> '서비스ID' AS 서비스ID FROM cur.modified_list_raw),
     s AS (SELECT 서비스ID FROM cur.service_list WHERE 수정일시 >= (SELECT modified_since FROM cur.snapshot))
SELECT (SELECT modified_since FROM cur.snapshot) AS 필터_값,
       (SELECT count(*) FROM f) AS 필터_조회_건수,
       (SELECT count(*) FROM s) AS 목록에서_수정일시_이상_건수,
       (SELECT count(*) FROM f WHERE 서비스ID NOT IN (SELECT 서비스ID FROM s)) AS 필터에만_있음,
       (SELECT count(*) FROM s WHERE 서비스ID NOT IN (SELECT 서비스ID FROM f)) AS 목록에만_있음;
