-- 필드별 결측과 형식. 지원조건 코드(JA*)는 condition_values.sql에서 따로 본다.
-- 결측은 키 없음, JSON null, 공백만 있는 문자열을 나눠 센다.

-- name: setup
CREATE TEMP TABLE cells AS
WITH recs AS (
    SELECT 'serviceList' AS 경로, rec FROM cur.service_list_raw
    UNION ALL SELECT 'serviceDetail', rec FROM cur.service_detail_raw
    UNION ALL SELECT 'supportConditions', rec FROM cur.support_conditions_raw
), fields AS (
    SELECT DISTINCT 경로, unnest(json_keys(rec)) AS 필드 FROM recs
)
SELECT r.경로, f.필드, json_type(r.rec -> f.필드) AS 타입, r.rec ->> f.필드 AS 값
FROM recs r JOIN fields f USING (경로)
WHERE f.필드 NOT LIKE 'JA%';

-- name: 필드별 결측 비율과 길이
SELECT 경로,
       필드,
       count(*) AS 전체,
       count(*) FILTER (WHERE 타입 IS NULL) AS 키_없음,
       count(*) FILTER (WHERE 타입 = 'NULL') AS json_null,
       count(*) FILTER (WHERE 타입 = 'VARCHAR' AND regexp_matches(값, '^\s*$')) AS 공백_문자열,
       round(100.0 * count(*) FILTER (WHERE 타입 IS NULL OR 타입 = 'NULL' OR regexp_matches(값, '^\s*$')) / count(*), 1) AS 결측_비율,
       string_agg(DISTINCT 타입, ',' ORDER BY 타입) AS json_타입,
       quantile_disc(length(값), 0.5) AS 문자수_중앙값,
       quantile_disc(length(값), 0.95) AS 문자수_p95,
       max(length(값)) AS 문자수_최대,
       max(strlen(값)) AS 바이트_최대
FROM cells
GROUP BY 경로, 필드
ORDER BY 경로, 필드;

-- name: 날짜 필드의 형식 (숫자를 9로 바꾼 모양)
SELECT 경로, 필드, regexp_replace(값, '[0-9]', '9', 'g') AS 형식, count(*) AS 건수, min(값) AS 최솟값, max(값) AS 최댓값
FROM cells
WHERE 필드 IN ('등록일시', '수정일시')
GROUP BY ALL
ORDER BY 경로, 필드, 건수 DESC;

-- name: 목록 수정일시의 연도 분포
SELECT left(수정일시, 4) AS 수정_연도, count(*) AS 건수
FROM cur.service_list
GROUP BY 1
ORDER BY 1;

-- name: 목록 등록일시의 연도 분포
SELECT left(등록일시, 4) AS 등록_연도, count(*) AS 건수
FROM cur.service_list
GROUP BY 1
ORDER BY 1;

-- name: 링크 필드의 형식
SELECT 경로,
       필드,
       CASE
           WHEN 타입 IS NULL OR 타입 = 'NULL' THEN '값 없음'
           WHEN regexp_matches(값, '^\s*$') THEN '공백 문자열'
           WHEN regexp_matches(trim(값), '^https://\S+$') THEN 'https:// URL 하나'
           WHEN regexp_matches(trim(값), '^http://\S+$') THEN 'http:// URL 하나'
           WHEN regexp_matches(trim(값), '^www\.\S+$|^[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$') THEN '스킴 없는 주소'
           ELSE '그 외 (여러 주소·설명문 등)'
       END AS 형식,
       count(*) AS 건수,
       min(trim(값)) AS 예시
FROM cells
WHERE 필드 IN ('상세조회URL', '온라인신청사이트URL')
GROUP BY ALL
ORDER BY 경로, 필드, 건수 DESC;

-- name: 링크 필드 '그 외' 예시
SELECT 경로, 필드, trim(값) AS 값
FROM cells
WHERE 필드 IN ('상세조회URL', '온라인신청사이트URL')
  AND 타입 = 'VARCHAR'
  AND NOT regexp_matches(값, '^\s*$')
  AND NOT regexp_matches(trim(값), '^https?://\S+$|^www\.\S+$|^[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$')
ORDER BY md5(값)
LIMIT 10;

-- name: 여러 값을 || 로 이어 붙인 필드
SELECT 경로, 필드, count(*) FILTER (WHERE contains(값, '||')) AS 포함_건수, count(*) AS 전체
FROM cells
WHERE 타입 = 'VARCHAR'
GROUP BY ALL
HAVING 포함_건수 > 0
ORDER BY 경로, 필드;

-- name: 긴 텍스트의 서식 기호 사용 (상세)
SELECT 필드,
       count(*) FILTER (WHERE contains(값, chr(10))) AS 줄바꿈,
       count(*) FILTER (WHERE contains(값, '○')) AS "○",
       count(*) FILTER (WHERE regexp_matches(값, '(^|\n)\s*-\s')) AS "- 목록",
       count(*) FILTER (WHERE contains(값, '※')) AS "※",
       count(*) FILTER (WHERE regexp_matches(값, '<[a-zA-Z/][^>]*>')) AS html_태그,
       count(*) FILTER (WHERE regexp_matches(값, '&[a-z]+;|&#[0-9]+;')) AS html_엔티티
FROM cells
WHERE 경로 = 'serviceDetail' AND 필드 IN ('지원대상', '선정기준', '지원내용', '신청방법', '구비서류', '서비스목적')
GROUP BY 필드
ORDER BY 필드;

-- name: 필드별 가장 긴 텍스트 (상세)
SELECT 필드, 서비스ID, 문자수, 앞부분
FROM (
    SELECT 필드, rec_id AS 서비스ID, length(값) AS 문자수, left(값, 80) AS 앞부분,
           row_number() OVER (PARTITION BY 필드 ORDER BY length(값) DESC, rec_id) AS rn
    FROM (
        SELECT d.서비스ID AS rec_id, f.필드, f.값
        FROM cur.service_detail d,
        LATERAL (VALUES ('지원대상', d.지원대상), ('선정기준', d.선정기준), ('지원내용', d.지원내용),
                        ('신청방법', d.신청방법), ('구비서류', d.구비서류)) AS f(필드, 값)
    )
)
WHERE rn = 1
ORDER BY 필드;
