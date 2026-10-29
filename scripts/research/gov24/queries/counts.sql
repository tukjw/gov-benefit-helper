-- 경로별 건수와 페이지 순회 결과.
-- totalCount는 응답의 전체 건수, 받은_레코드는 실제로 받은 data 원소 수다.

-- name: 경로별 건수
SELECT path AS 경로,
       min(totalCount) AS totalCount_최소,
       max(totalCount) AS totalCount_최대,
       sum(records) AS 받은_레코드,
       count(*) AS 쪽수,
       max(perPage) AS perPage
FROM cur.pages
GROUP BY path
ORDER BY path;

-- name: 쪽별 건수가 perPage와 다른 쪽 (마지막 쪽 제외)
SELECT p.path AS 경로, p.page AS 쪽, p.perPage, p.currentCount, p.records AS 받은_레코드
FROM cur.pages p
WHERE p.records <> p.perPage
  AND p.page < (SELECT max(page) FROM cur.pages q WHERE q.path = p.path)
ORDER BY p.path, p.page;

-- name: 경로 안에서 같은 서비스ID가 두 번 이상 나온 레코드 수
SELECT 'serviceList' AS 경로, count(*) - count(DISTINCT 서비스ID) AS 중복_레코드 FROM cur.service_list
UNION ALL
SELECT 'serviceDetail', count(*) - count(DISTINCT 서비스ID) FROM cur.service_detail
UNION ALL
SELECT 'supportConditions', count(*) - count(DISTINCT 서비스ID) FROM cur.support_conditions
ORDER BY 1;
