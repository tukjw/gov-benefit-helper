-- 대상 지역을 판별할 근거. 지원조건 코드에는 지역 항목이 없다(명세).
-- 소관기관은 혜택을 운영하는 기관이며, 대상 지역과 같다고 가정하지 않는다.

-- name: 소관기관유형별 건수
SELECT 소관기관유형, count(*) AS 서비스_수, count(DISTINCT 소관기관명) AS 기관_수
FROM cur.service_list
GROUP BY 1
ORDER BY 서비스_수 DESC;

-- name: 소관기관명에서 시도를 읽을 수 있는지
SELECT 소관기관유형,
       count(*) FILTER (WHERE regexp_matches(소관기관명, '^(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청|충북|충남|전라|전북|전남|경상|경북|경남|제주)')) AS 시도로_시작,
       count(*) AS 서비스_수,
       min(소관기관명) FILTER (WHERE NOT regexp_matches(소관기관명, '^(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청|충북|충남|전라|전북|전남|경상|경북|경남|제주)')) AS 시도로_시작하지_않는_예시
FROM cur.service_list
GROUP BY 1
ORDER BY 서비스_수 DESC;

-- name: 지원대상 문구의 거주·주민등록 요건 언급
SELECT l.소관기관유형,
       count(*) AS 서비스_수,
       count(*) FILTER (WHERE regexp_matches(d.지원대상, '거주|주민등록|관내|전입|주소지')) AS 거주_언급,
       round(100.0 * count(*) FILTER (WHERE regexp_matches(d.지원대상, '거주|주민등록|관내|전입|주소지')) / count(*), 1) AS 거주_언급_비율,
       count(*) FILTER (WHERE d.자치법규 IS NOT NULL AND NOT regexp_matches(d.자치법규, '^\s*$')) AS 자치법규_있음
FROM cur.service_list l
JOIN cur.service_detail d USING (서비스ID)
GROUP BY 1
ORDER BY 서비스_수 DESC;

-- name: 지자체 혜택인데 지원대상에 거주 요건이 없는 예시
SELECT l.서비스ID, l.소관기관명, left(d.지원대상, 100) AS 지원대상_앞부분
FROM cur.service_list l
JOIN cur.service_detail d USING (서비스ID)
WHERE l.소관기관유형 IN ('시군구', '광역시도')
  AND NOT regexp_matches(coalesce(d.지원대상, ''), '거주|주민등록|관내|전입|주소지')
ORDER BY md5(l.서비스ID || ':region-no-residence')
LIMIT 5;

-- name: 중앙행정기관 혜택인데 지원대상에 거주 요건이 있는 예시
SELECT l.서비스ID, l.소관기관명, left(d.지원대상, 100) AS 지원대상_앞부분
FROM cur.service_list l
JOIN cur.service_detail d USING (서비스ID)
WHERE l.소관기관유형 = '중앙행정기관'
  AND regexp_matches(coalesce(d.지원대상, ''), '거주|주민등록|관내|전입|주소지')
ORDER BY md5(l.서비스ID || ':region-central-residence')
LIMIT 5;
