-- 신청기한 원문을 정규식 규칙으로 분류한다. 규칙은 위에서부터 처음 맞는 것 하나를 쓴다.
-- 결과는 '분류:세부 규칙' 문자열이다. 분류는 명시 날짜·상시·조건부·미확인이고,
-- 신청 절차가 없다고 적힌 경우는 기한이 아니므로 '신청 불필요'로 따로 센다.
-- 오분류율은 samples.sql의 표본을 원문과 대조해 보고서에 적는다.

-- name: setup
CREATE TEMP MACRO deadline_rule(t) AS CASE
    WHEN t IS NULL OR regexp_matches(t, '^\s*$')
        THEN '미확인:값 없음'
    WHEN regexp_matches(t, '신청\s*(절차\s*)?(가\s*)?(불필요|불요)|신청\s*절차\s*(가|는)?\s*없|별도\s*(의)?\s*신청\s*(절차)?\s*(가|는)?\s*(없|불필요)|자동\s*(지급|지원|감면|부과|적용|선정)')
        THEN '신청 불필요:신청 절차 없음·자동 지급'
    WHEN regexp_matches(t, '(19|20)\d{2}\s*[.\-/년]\s*\d{1,2}\s*[.\-/월]\s*\d{1,2}')
         AND regexp_matches(t, '예산|소진|선착순|조기\s*마감|마감\s*될\s*수')
        THEN '명시 날짜:연월일 (조기 마감 조건 포함)'
    WHEN regexp_matches(t, '(19|20)\d{2}\s*[.\-/년]\s*\d{1,2}\s*[.\-/월]\s*\d{1,2}')
        THEN '명시 날짜:연월일'
    -- 기간 숫자가 있어야 사건 기준으로 본다. '시행일로부터 지정기간'처럼 기간이 없으면 기한을 알 수 없다.
    WHEN regexp_matches(t, '(\d+|한|두|세)\s*(일|개월|달|월|년|주)\s*(이내|내에|안에|전부터|전까지|전)')
         OR regexp_matches(t, '(로부터|후)\s*\d+\s*(일|개월|달|월|년|주)')
        THEN '조건부:사건 기준 기한'
    WHEN regexp_matches(t, '예산\s*(소진|한도|범위|내|의\s*범위)|소진\s*(시|될|되면|까지)|선착순|조기\s*마감|마감\s*될\s*수')
        THEN '조건부:예산 소진·선착순'
    -- '접수시기'·'접수시'의 '수시'를 상시로 읽지 않는다.
    WHEN regexp_matches(replace(t, '접수시', ''), '상시|연중|수시|언제든|365일|항시')
        THEN '상시:상시·연중·수시'
    WHEN regexp_matches(t, '(19|20)\d{2}\s*[.\-/년]\s*\d{1,2}')
        THEN '미확인:연월만 명시'
    WHEN regexp_matches(t, '^\s*(해당\s*없음|해당사항\s*없음|없음|-)\s*$')
        THEN '미확인:없음·해당 없음'
    WHEN regexp_matches(t, '상이|다를\s*수|다름|따라\s*다|마다\s*다|별로\s*다')
        THEN '미확인:기관·사업별 상이'
    WHEN regexp_matches(t, '공고|공지|별도|추후|안내|홈페이지|참고|참조|따름|따라|문의|확인')
        THEN '미확인:공고·별도 안내·문의'
    WHEN regexp_matches(t, '매년|매월|매달|분기|해마다|연\s*\d+\s*회|상반기|하반기|\d{1,2}\s*월|연초|연말|설|추석|초순|중순|하순|학기')
        THEN '미확인:반복 시기 (연도·날짜 없음)'
    ELSE '미확인:기타'
END;
CREATE TEMP TABLE deadlines AS
SELECT 서비스ID, 신청기한, deadline_rule(신청기한) AS 규칙, split_part(deadline_rule(신청기한), ':', 1) AS 분류
FROM cur.service_list;

-- name: 분류별 비율
SELECT 분류, count(*) AS 건수, round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS 비율
FROM deadlines
GROUP BY 분류
ORDER BY 건수 DESC;

-- name: 세부 규칙별 건수와 자주 나온 원문
SELECT 규칙,
       count(*) AS 건수,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS 비율,
       count(DISTINCT 신청기한) AS 원문_종류
FROM deadlines
GROUP BY 규칙
ORDER BY 건수 DESC;

-- name: 세부 규칙별 원문 예시 (규칙마다 자주 나온 순 3개)
SELECT 규칙, 신청기한, 건수
FROM (SELECT 규칙, 신청기한, count(*) AS 건수 FROM deadlines GROUP BY ALL)
QUALIFY row_number() OVER (PARTITION BY 규칙 ORDER BY 건수 DESC, 신청기한) <= 3
ORDER BY 규칙, 건수 DESC, 신청기한;

-- name: 명시 날짜에 나온 가장 늦은 연도와 수집 연도 비교
SELECT CASE
           WHEN y < s THEN '지난 연도만 (이미 지난 기한)'
           WHEN y = s THEN '수집 연도'
           ELSE '다음 해 이후'
       END AS 구분,
       count(*) AS 건수,
       min(신청기한) AS 예시
FROM (
    SELECT 신청기한,
           list_max(list_transform(regexp_extract_all(신청기한, '(?:19|20)\d{2}'), x -> x::INTEGER)) AS y,
           (SELECT year(snapshot_date) FROM cur.snapshot) AS s
    FROM deadlines
    WHERE 분류 = '명시 날짜'
)
GROUP BY 1
ORDER BY 1;
