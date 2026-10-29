-- 여러 스냅샷의 목록에서 사라짐·재등장 집계. series 하위 명령에서만 실행한다.
-- obs(순번, 서비스ID, 서비스명, 등록일시, 수정일시): 스냅샷별 목록. snapshots(순번, 스냅샷, started_at).
-- 부재 구간은 한 서비스ID가 연속한 스냅샷에서 목록에 없던 구간이다. 유형은 다음과 같다.
--   처음부터 없음: 첫 스냅샷부터 없다가 나중에 나타남
--   재등장: 있다가 사라진 뒤 다시 나타남
--   마지막까지 없음: 있다가 사라진 뒤 마지막 스냅샷까지 없음

-- name: setup
CREATE TEMP TABLE grid AS
SELECT i.서비스ID, s.순번, o.서비스ID IS NOT NULL AS 있음
FROM (SELECT DISTINCT 서비스ID FROM obs) i
CROSS JOIN snapshots s
LEFT JOIN obs o ON o.서비스ID = i.서비스ID AND o.순번 = s.순번;
CREATE TEMP TABLE absences AS
WITH g AS (
    SELECT *, 순번 - row_number() OVER (PARTITION BY 서비스ID, 있음 ORDER BY 순번) AS grp
    FROM grid
)
SELECT 서비스ID, min(순번) AS 시작, max(순번) AS 끝, count(*) AS 연속_부재,
       CASE
           WHEN min(순번) = 0 THEN '처음부터 없음'
           WHEN max(순번) = (SELECT max(순번) FROM snapshots) THEN '마지막까지 없음'
           ELSE '재등장'
       END AS 유형
FROM g
WHERE NOT 있음
GROUP BY 서비스ID, grp;

-- name: 스냅샷별 목록 건수
SELECT s.순번, s.스냅샷, count(o.서비스ID) AS 목록_건수
FROM snapshots s LEFT JOIN obs o USING (순번)
GROUP BY ALL
ORDER BY s.순번;

-- name: 시점별 사라짐·재등장·첫 등장 건수 (직전 스냅샷 대비)
SELECT s.순번, s.스냅샷,
       (SELECT count(*) FROM absences a WHERE a.시작 = s.순번 AND a.유형 <> '처음부터 없음') AS 사라짐,
       (SELECT count(*) FROM absences a WHERE a.끝 + 1 = s.순번 AND a.유형 = '재등장') AS 재등장,
       (SELECT count(*) FROM absences a WHERE a.끝 + 1 = s.순번 AND a.유형 = '처음부터 없음') AS 첫_등장
FROM snapshots s
WHERE s.순번 > 0
ORDER BY s.순번;

-- name: 부재 구간 유형별 건수
SELECT 유형, count(DISTINCT 서비스ID) AS 서비스_수, count(*) AS 구간_수,
       min(연속_부재) AS 최소_연속_부재, max(연속_부재) AS 최대_연속_부재
FROM absences
GROUP BY ALL
ORDER BY 유형;

-- name: 재등장 사례 (전부)
SELECT a.서비스ID,
       (SELECT 스냅샷 FROM snapshots WHERE 순번 = a.시작) AS 사라진_스냅샷,
       (SELECT 스냅샷 FROM snapshots WHERE 순번 = a.끝 + 1) AS 다시_나타난_스냅샷,
       a.연속_부재,
       b.수정일시 AS 사라지기_전_수정일시,
       r.수정일시 AS 다시_나타날_때_수정일시,
       b.서비스명 AS 사라지기_전_서비스명,
       nullif(r.서비스명, b.서비스명) AS 바뀐_서비스명
FROM absences a
JOIN obs b ON b.서비스ID = a.서비스ID AND b.순번 = a.시작 - 1
JOIN obs r ON r.서비스ID = a.서비스ID AND r.순번 = a.끝 + 1
WHERE a.유형 = '재등장'
ORDER BY a.시작, a.서비스ID;

-- name: 마지막 스냅샷까지 없는 서비스 (전부)
SELECT a.서비스ID,
       (SELECT 스냅샷 FROM snapshots WHERE 순번 = a.시작 - 1) AS 마지막_확인_스냅샷,
       a.연속_부재,
       b.서비스명, b.등록일시, b.수정일시
FROM absences a
JOIN obs b ON b.서비스ID = a.서비스ID AND b.순번 = a.시작 - 1
WHERE a.유형 = '마지막까지 없음'
ORDER BY a.시작, a.서비스ID;

-- name: 첫 등장 서비스 (전부). 등록일시가 첫 스냅샷 시작 이후면 관찰 중 등록
SELECT a.서비스ID,
       (SELECT 스냅샷 FROM snapshots WHERE 순번 = a.끝 + 1) AS 첫_등장_스냅샷,
       CASE WHEN f.등록일시 >= (SELECT started_at FROM snapshots WHERE 순번 = 0) THEN '관찰 중 등록' ELSE '관찰 전 등록' END AS 구분,
       f.등록일시, f.수정일시, f.서비스명
FROM absences a
JOIN obs f ON f.서비스ID = a.서비스ID AND f.순번 = a.끝 + 1
WHERE a.유형 = '처음부터 없음'
ORDER BY a.끝, a.서비스ID;
