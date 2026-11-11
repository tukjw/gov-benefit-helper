-- 발송 대기로 남은 합성 묶음 수(#137). run.sh가 시나리오가 끝났는지 볼 때 쓴다.
SELECT COUNT(*) FROM new_benefit_digest d JOIN user_account u ON u.id = d.user_id
WHERE u.email LIKE 'notify-%@loadtest.invalid' AND d.status = 'PENDING';
