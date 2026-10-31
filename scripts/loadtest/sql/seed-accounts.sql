-- 부하 테스트 계정 1,000개(loadtest-0001@loadtest.invalid ~ loadtest-1000@loadtest.invalid)를 넣는다(결정 0037).
-- accounts.sh seed가 앞에 SET @password_hash = '{bcrypt}...';를 붙여 실행한다. 모든 계정이 같은 해시를 쓴다.
-- .invalid는 예약된 최상위 도메인이라 실제 주소와 겹치지 않는다. 이메일은 앱처럼 소문자로 넣는다(V4).
-- 계정 수는 k6의 가상 사용자 수 상한(MAX_VUS)과 같다. 가상 사용자 번호로 계정을 고른다.
SET SESSION cte_max_recursion_depth = 2000;

INSERT INTO user_account (email, password_hash, created_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT CONCAT('loadtest-', LPAD(n, 4, '0'), '@loadtest.invalid'), @password_hash, NOW(6)
FROM seq;
