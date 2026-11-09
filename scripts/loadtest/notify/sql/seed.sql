-- 알림 발송 장애 측정(#137)의 합성 데이터를 넣는다. data.sh seed가 앞에 SET @now, @users를 붙여 실행한다.
-- @now는 부하 생성 VM의 KST 시각이다. DB 서버의 시간대와 상관없이 앱(Asia/Seoul)과 같은 기준으로 묶은 날과 보낼 시각을 정한다.
--
-- 1. 가짜 새 혜택 3건: new_benefit에 아직 없는 혜택 중 ID 순 앞 3건. 대상을 고른 것으로 넣어(targets_selected_at)
--    대상 선정이 고르지 않는다. created_at '2000-01-01 00:00:00'이 측정용 표시이고, cleanup.sql은 이 값으로 지운다.
-- 2. 합성 사용자 @users명: notify-00001@loadtest.invalid부터. 알림을 켰고, 비밀번호 해시가 BCrypt가 아니라 로그인할 수 없다.
-- 3. 사용자마다 그날 묶음 하나(발송 대기, 바로 보낼 차례)와 가짜 새 혜택 3건의 알림 항목.
-- 한 트랜잭션이라 발송 작업은 모두 넣은 뒤에야 묶음을 본다. 묶음 ID 순이 곧 사용자 번호 순이고 발송 순서다.
SET SESSION cte_max_recursion_depth = 100000;

START TRANSACTION;

INSERT INTO new_benefit (benefit_id, detected_run_id, status, targets_selected_at, created_at, updated_at)
SELECT b.id, (SELECT MAX(id) FROM collection_run), 'READY', @now, '2000-01-01 00:00:00', '2000-01-01 00:00:00'
FROM benefit b
LEFT JOIN new_benefit nb ON nb.benefit_id = b.id
WHERE nb.benefit_id IS NULL
ORDER BY b.id
LIMIT 3;

INSERT INTO user_account (email, password_hash, new_benefit_notify, created_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @users)
SELECT CONCAT('notify-', LPAD(n, 5, '0'), '@loadtest.invalid'), '{bcrypt}notify-fault-no-login', TRUE, @now
FROM seq;

INSERT INTO new_benefit_digest (user_id, digest_date, status, attempt_count, next_attempt_at, created_at)
SELECT id, DATE(@now), 'PENDING', 0, @now, @now
FROM user_account
WHERE email LIKE 'notify-%@loadtest.invalid'
ORDER BY id;

INSERT INTO new_benefit_target (user_id, benefit_id, digest_id)
SELECT d.user_id, nb.benefit_id, d.id
FROM new_benefit_digest d
JOIN user_account u ON u.id = d.user_id
CROSS JOIN new_benefit nb
WHERE u.email LIKE 'notify-%@loadtest.invalid'
  AND nb.created_at = '2000-01-01 00:00:00';

COMMIT;
