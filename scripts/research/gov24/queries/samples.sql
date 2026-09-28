-- 공고 대조와 신청기한 검증에 쓸 표본. md5(서비스ID || ':' || 용도) 순으로 뽑아 같은 스냅샷이면 같은 표본이 나온다.
-- condition_values.sql의 condition_cells·group_fill, deadline_classes.sql의 deadlines를 쓴다.
-- 조건 코드는 그룹 전체가 Y인 서비스가 많아 무작위로 뽑으면 의미를 가를 수 없다.
-- 그래서 코드별 표본은 그룹의 일부만 Y인 서비스(대상을 좁힌 경우)에서 뽑는다.

-- name: 조건 코드 대조 표본 — 개인 대상 코드별 2건 (그룹의 일부만 Y인 서비스)
SELECT c.code, c.이름, c.서비스ID, l.서비스명, g.y_코드 AS 그룹의_Y_코드
FROM condition_cells c
JOIN group_fill g ON g.서비스ID = c.서비스ID AND g.그룹 = c.그룹
JOIN cur.service_list l ON l.서비스ID = c.서비스ID
WHERE c.대상 = '개인' AND c.그룹 <> '연령' AND c.값 = 'Y' AND g.y_수 < g.코드_수
QUALIFY row_number() OVER (PARTITION BY c.code ORDER BY md5(c.서비스ID || ':condition:' || c.code)) <= 2
ORDER BY c.code, md5(c.서비스ID || ':condition:' || c.code);

-- name: 그룹 전부 Y 표본 — 성별·소득·가구가 전부 Y이고 연령 0~120 (3건)
WITH all_y AS (
    SELECT 서비스ID
    FROM group_fill
    WHERE 그룹 IN ('성별', '소득', '가구')
    GROUP BY 서비스ID
    HAVING count(*) = 3 AND bool_and(y_수 = 코드_수)
)
SELECT a.서비스ID, l.서비스명
FROM all_y a
JOIN cur.service_list l USING (서비스ID)
JOIN condition_cells s ON s.서비스ID = a.서비스ID AND s.code = 'JA0110' AND s.값 = '0'
JOIN condition_cells e ON e.서비스ID = a.서비스ID AND e.code = 'JA0111' AND e.값 = '120'
ORDER BY md5(a.서비스ID || ':all-y')
LIMIT 3;

-- name: 개인 대상 코드가 전부 빈 서비스 표본 (3건)
-- 모든 코드가 빈 서비스는 없다(condition_values.sql). 개인 대상 코드만 빈 경우의 의미를 본다.
SELECT n.서비스ID, l.서비스명, l.사용자구분
FROM (SELECT 서비스ID FROM condition_cells WHERE 대상 = '개인' GROUP BY 서비스ID HAVING count(값) = 0) n
JOIN cur.service_list l USING (서비스ID)
ORDER BY md5(n.서비스ID || ':personal-null')
LIMIT 3;

-- name: 연령 경계 표본 — 시작 > 0이고 종료 < 120 (3건)
SELECT s.서비스ID, l.서비스명, s.값 AS 시작, e.값 AS 종료
FROM condition_cells s
JOIN condition_cells e ON e.서비스ID = s.서비스ID AND e.code = 'JA0111'
JOIN cur.service_list l ON l.서비스ID = s.서비스ID
WHERE s.code = 'JA0110' AND s.값::INTEGER > 0 AND e.값::INTEGER < 120
ORDER BY md5(s.서비스ID || ':age')
LIMIT 3;

-- name: 신청기한 분류 검증 표본 — 분류별 10건
-- 1차 표본(':deadline')에서 찾은 오분류로 규칙을 고친 뒤, 고친 규칙은 새 표본(':deadline-v2')으로 검증한다.
SELECT 분류, 규칙, 서비스ID, 신청기한
FROM deadlines
QUALIFY row_number() OVER (PARTITION BY 분류 ORDER BY md5(서비스ID || ':deadline-v2')) <= 10
ORDER BY 분류, md5(서비스ID || ':deadline-v2');
