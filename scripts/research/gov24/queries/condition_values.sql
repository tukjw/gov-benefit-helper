-- 지원조건 코드(JA*)의 실제 값. 코드 이름은 공식 명세의 설명이다.
-- 값의 의미(Y·null·연령 0~120)는 여기서 정하지 않고, 표본을 공고와 대조해 확인한다.
-- JA1201의 명세 표기는 '음식적업'이다. 음식점업의 오타로 보고 이 표에서는 '음식점업'으로 적는다.

-- name: setup
CREATE TEMP TABLE codes (code VARCHAR, 이름 VARCHAR, 그룹 VARCHAR, 대상 VARCHAR);
INSERT INTO codes VALUES
    ('JA0101', '남성', '성별', '개인'), ('JA0102', '여성', '성별', '개인'),
    ('JA0110', '대상연령(시작)', '연령', '개인'), ('JA0111', '대상연령(종료)', '연령', '개인'),
    ('JA0201', '중위소득 0~50%', '소득', '개인'), ('JA0202', '중위소득 51~75%', '소득', '개인'),
    ('JA0203', '중위소득 76~100%', '소득', '개인'), ('JA0204', '중위소득 101~200%', '소득', '개인'),
    ('JA0205', '중위소득 200% 초과', '소득', '개인'),
    ('JA0301', '예비부모/난임', '생애·직업·건강', '개인'), ('JA0302', '임산부', '생애·직업·건강', '개인'),
    ('JA0303', '출산/입양', '생애·직업·건강', '개인'), ('JA0313', '농업인', '생애·직업·건강', '개인'),
    ('JA0314', '어업인', '생애·직업·건강', '개인'), ('JA0315', '축산업인', '생애·직업·건강', '개인'),
    ('JA0316', '임업인', '생애·직업·건강', '개인'), ('JA0317', '초등학생', '생애·직업·건강', '개인'),
    ('JA0318', '중학생', '생애·직업·건강', '개인'), ('JA0319', '고등학생', '생애·직업·건강', '개인'),
    ('JA0320', '대학생/대학원생', '생애·직업·건강', '개인'), ('JA0322', '해당사항없음', '생애·직업·건강', '개인'),
    ('JA0326', '근로자/직장인', '생애·직업·건강', '개인'), ('JA0327', '구직자/실업자', '생애·직업·건강', '개인'),
    ('JA0328', '장애인', '생애·직업·건강', '개인'), ('JA0329', '국가보훈대상자', '생애·직업·건강', '개인'),
    ('JA0330', '질병/질환자', '생애·직업·건강', '개인'),
    ('JA0401', '다문화가족', '가구', '개인'), ('JA0402', '북한이탈주민', '가구', '개인'),
    ('JA0403', '한부모가정/조손가정', '가구', '개인'), ('JA0404', '1인가구', '가구', '개인'),
    ('JA0410', '해당사항없음', '가구', '개인'), ('JA0411', '다자녀가구', '가구', '개인'),
    ('JA0412', '무주택세대', '가구', '개인'), ('JA0413', '신규전입', '가구', '개인'),
    ('JA0414', '확대가족', '가구', '개인'),
    ('JA1101', '예비창업자', '창업 단계', '사업자·기업'), ('JA1102', '영업중', '창업 단계', '사업자·기업'),
    ('JA1103', '생계곤란/폐업예정자', '창업 단계', '사업자·기업'),
    ('JA1201', '음식점업', '소상공인 업종', '사업자·기업'), ('JA1202', '제조업', '소상공인 업종', '사업자·기업'),
    ('JA1299', '기타업종', '소상공인 업종', '사업자·기업'),
    ('JA2101', '중소기업', '기관 유형', '사업자·기업'), ('JA2102', '사회복지시설', '기관 유형', '사업자·기업'),
    ('JA2103', '기관/단체', '기관 유형', '사업자·기업'),
    ('JA2201', '제조업', '기업 업종', '사업자·기업'), ('JA2202', '농업,임업 및 어업', '기업 업종', '사업자·기업'),
    ('JA2203', '정보통신업', '기업 업종', '사업자·기업'), ('JA2299', '기타업종', '기업 업종', '사업자·기업');
CREATE TEMP TABLE condition_cells AS
SELECT r.rec ->> '서비스ID' AS 서비스ID, c.code, c.이름, c.그룹, c.대상,
       json_type(r.rec -> c.code) AS 타입, r.rec ->> c.code AS 값
FROM cur.support_conditions_raw r CROSS JOIN codes c;
-- 서비스·그룹별 채움 형태. 연령은 시작·종료 값으로 따로 본다.
CREATE TEMP TABLE group_fill AS
SELECT 서비스ID, 그룹, 대상,
       count(*) AS 코드_수,
       count(*) FILTER (WHERE 값 = 'Y') AS y_수,
       string_agg(code, ',' ORDER BY code) FILTER (WHERE 값 = 'Y') AS y_코드
FROM condition_cells
WHERE 그룹 <> '연령'
GROUP BY ALL;

-- name: 응답에 있지만 명세 코드표에 없는 JA 코드
SELECT code
FROM (SELECT DISTINCT unnest(json_keys(rec)) AS code FROM cur.support_conditions_raw)
WHERE code LIKE 'JA%'
EXCEPT SELECT code FROM codes
ORDER BY code;

-- name: 코드별 값 종류 (연령 코드의 숫자는 '숫자'로 묶음)
SELECT code, 이름, 대상, 타입 AS json_타입,
       CASE WHEN 그룹 = '연령' AND 값 IS NOT NULL THEN '(숫자)' ELSE coalesce(값, '(null)') END AS 값,
       count(*) AS 건수
FROM condition_cells
GROUP BY ALL
ORDER BY code, 건수 DESC;

-- name: 그룹별 채움 형태
SELECT 그룹, 대상,
       CASE WHEN y_수 = 0 THEN '전부 비어 있음' WHEN y_수 = 코드_수 THEN '전부 Y' ELSE '일부만 Y' END AS 형태,
       count(*) AS 서비스_수
FROM group_fill
GROUP BY ALL
ORDER BY 대상, 그룹, 형태;

-- name: 연령 시작·종료 조합
SELECT CASE
           WHEN s.값 IS NULL AND e.값 IS NULL THEN '둘 다 비어 있음'
           WHEN s.값 IS NULL OR e.값 IS NULL THEN '한쪽만 있음'
           WHEN s.값::INTEGER > e.값::INTEGER THEN '시작 > 종료'
           WHEN s.값::INTEGER = 0 AND e.값::INTEGER = 120 THEN '0~120'
           WHEN s.값::INTEGER = 0 THEN '종료만 제한 (0~N)'
           WHEN e.값::INTEGER = 120 THEN '시작만 제한 (N~120)'
           ELSE '시작·종료 모두 제한'
       END AS 형태,
       count(*) AS 서비스_수
FROM (SELECT 서비스ID, 값 FROM condition_cells WHERE code = 'JA0110') s
JOIN (SELECT 서비스ID, 값 FROM condition_cells WHERE code = 'JA0111') e USING (서비스ID)
GROUP BY 1
ORDER BY 서비스_수 DESC;

-- name: 연령 값 분포 (상위 15개)
SELECT code, 이름, 값::INTEGER AS 나이, count(*) AS 건수
FROM condition_cells
WHERE 그룹 = '연령' AND 값 IS NOT NULL
GROUP BY code, 이름, 값
QUALIFY row_number() OVER (PARTITION BY code ORDER BY count(*) DESC, 나이) <= 15
ORDER BY code, 건수 DESC, 나이;

-- name: 지원조건 코드가 전부 비어 있는 서비스
SELECT count(*) AS 서비스_수
FROM (SELECT 서비스ID FROM condition_cells GROUP BY 서비스ID HAVING count(값) = 0);

-- name: 개인 대상 코드 채움과 목록의 사용자구분
WITH personal AS (
    SELECT 서비스ID, count(값) AS 채운_코드_수
    FROM condition_cells WHERE 대상 = '개인' GROUP BY 서비스ID
)
SELECT l.사용자구분,
       count(*) FILTER (WHERE p.채운_코드_수 = 0) AS 개인코드_전부_빔,
       count(*) FILTER (WHERE p.채운_코드_수 > 0) AS 개인코드_있음
FROM cur.service_list l JOIN personal p USING (서비스ID)
GROUP BY 1
ORDER BY count(*) DESC, 1;

-- name: '해당사항없음' 코드가 같은 그룹의 다른 코드와 함께 Y인 경우
SELECT h.code AS 해당사항없음_코드, g.그룹,
       CASE WHEN g.y_수 = 1 THEN '해당사항없음만 Y' WHEN g.y_수 = g.코드_수 THEN '그룹 전부 Y' ELSE '다른 코드 일부와 함께 Y' END AS 형태,
       count(*) AS 서비스_수
FROM condition_cells h
JOIN group_fill g ON g.서비스ID = h.서비스ID AND g.그룹 = h.그룹
WHERE h.code IN ('JA0322', 'JA0410') AND h.값 = 'Y'
GROUP BY ALL
ORDER BY 해당사항없음_코드, 서비스_수 DESC;
