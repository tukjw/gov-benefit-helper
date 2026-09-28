-- 세 경로 간 서비스ID 연결과 겹치는 필드의 값 비교.

-- name: 서비스ID가 없거나 빈 레코드
SELECT 'serviceList' AS 경로, count(*) FILTER (WHERE 서비스ID IS NULL OR trim(서비스ID) = '') AS 건수 FROM cur.service_list
UNION ALL
SELECT 'serviceDetail', count(*) FILTER (WHERE 서비스ID IS NULL OR trim(서비스ID) = '') FROM cur.service_detail
UNION ALL
SELECT 'supportConditions', count(*) FILTER (WHERE 서비스ID IS NULL OR trim(서비스ID) = '') FROM cur.support_conditions
ORDER BY 1;

-- name: 세 경로의 서비스ID 포함 조합
WITH ids AS (
    SELECT 서비스ID FROM cur.service_list
    UNION SELECT 서비스ID FROM cur.service_detail
    UNION SELECT 서비스ID FROM cur.support_conditions
)
SELECT 서비스ID IN (SELECT 서비스ID FROM cur.service_list) AS 목록,
       서비스ID IN (SELECT 서비스ID FROM cur.service_detail) AS 상세,
       서비스ID IN (SELECT 서비스ID FROM cur.support_conditions) AS 지원조건,
       count(*) AS 서비스_수
FROM ids
GROUP BY ALL
ORDER BY 서비스_수 DESC, 목록, 상세, 지원조건;

-- name: 한 경로에라도 빠진 서비스ID 예시 (최대 10건)
WITH ids AS (
    SELECT 서비스ID FROM cur.service_list
    UNION SELECT 서비스ID FROM cur.service_detail
    UNION SELECT 서비스ID FROM cur.support_conditions
)
SELECT 서비스ID,
       서비스ID IN (SELECT 서비스ID FROM cur.service_list) AS 목록,
       서비스ID IN (SELECT 서비스ID FROM cur.service_detail) AS 상세,
       서비스ID IN (SELECT 서비스ID FROM cur.support_conditions) AS 지원조건
FROM ids
WHERE NOT (목록 AND 상세 AND 지원조건)
ORDER BY 서비스ID
LIMIT 10;

-- name: 서비스ID 형식
SELECT length(서비스ID) AS 길이,
       CASE WHEN regexp_matches(서비스ID, '^[0-9]+$') THEN '숫자' ELSE regexp_extract(서비스ID, '^([A-Z]+)', 1) || '+숫자' END AS 형태,
       count(*) AS 건수,
       min(서비스ID) AS 예시
FROM cur.service_list
GROUP BY ALL
ORDER BY 건수 DESC, 형태;

-- name: 목록·상세의 같은 이름 필드 값 비교
WITH pairs AS (
    SELECT l.서비스ID, f.필드, f.목록값, f.상세값
    FROM cur.service_list l
    JOIN cur.service_detail d USING (서비스ID),
    LATERAL (VALUES
        ('서비스명', l.서비스명, d.서비스명),
        ('지원유형', l.지원유형, d.지원유형),
        ('지원대상', l.지원대상, d.지원대상),
        ('선정기준', l.선정기준, d.선정기준),
        ('지원내용', l.지원내용, d.지원내용),
        ('신청방법', l.신청방법, d.신청방법),
        ('신청기한', l.신청기한, d.신청기한),
        ('소관기관명', l.소관기관명, d.소관기관명),
        ('수정일시(날짜)', strftime(strptime(l.수정일시, '%Y%m%d%H%M%S'), '%Y-%m-%d'), d.수정일시)
    ) AS f(필드, 목록값, 상세값)
)
SELECT 필드,
       count(*) AS 비교_건수,
       count(*) FILTER (WHERE 목록값 IS DISTINCT FROM 상세값) AS 다른_건수
FROM pairs
GROUP BY 필드
ORDER BY 필드;

-- name: 목록·상세 신청방법 값 예시 (목록은 분류값, 상세는 설명문)
SELECT l.서비스ID, l.신청방법 AS 목록_신청방법, left(d.신청방법, 80) AS 상세_신청방법_앞부분
FROM cur.service_list l
JOIN cur.service_detail d USING (서비스ID)
WHERE l.신청방법 IS DISTINCT FROM d.신청방법
ORDER BY md5(l.서비스ID || ':method-example')
LIMIT 5;

-- name: 목록·상세 지원유형이 다른 조합
SELECT l.지원유형 AS 목록_지원유형, d.지원유형 AS 상세_지원유형, count(*) AS 건수
FROM cur.service_list l
JOIN cur.service_detail d USING (서비스ID)
WHERE l.지원유형 IS DISTINCT FROM d.지원유형
GROUP BY ALL
ORDER BY 건수 DESC, 목록_지원유형
LIMIT 10;

-- name: 목록·지원조건 서비스명 비교
SELECT count(*) AS 비교_건수,
       count(*) FILTER (WHERE l.서비스명 IS DISTINCT FROM c.서비스명) AS 다른_건수
FROM cur.service_list l
JOIN cur.support_conditions c USING (서비스ID);
