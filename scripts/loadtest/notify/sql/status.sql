-- 측정이 끝났을 때 합성 묶음의 상태(#137). count.py가 가짜 SES 기록과 수신 주소로 맞춘다.
-- 열: email, status, attempt_count, last_error, accepted_at, next_attempt_at, new_benefit_notify
SELECT u.email, d.status, d.attempt_count, IFNULL(d.last_error, ''), IFNULL(DATE_FORMAT(d.accepted_at, '%Y-%m-%dT%H:%i:%s.%f'), ''),
       DATE_FORMAT(d.next_attempt_at, '%Y-%m-%dT%H:%i:%s.%f'), u.new_benefit_notify
FROM new_benefit_digest d
JOIN user_account u ON u.id = d.user_id
WHERE u.email LIKE 'notify-%@loadtest.invalid'
ORDER BY d.id;
