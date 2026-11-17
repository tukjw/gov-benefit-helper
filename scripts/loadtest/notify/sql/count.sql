-- 남은 합성 데이터 수(#137). 측정이 끝난 뒤 모두 0이어야 실제 SES 설정으로 되돌린다(switch.sh off).
SELECT 'accounts', COUNT(*) FROM user_account WHERE email LIKE 'notify-%@loadtest.invalid'
UNION ALL
SELECT 'digests', COUNT(*) FROM new_benefit_digest d JOIN user_account u ON u.id = d.user_id
WHERE u.email LIKE 'notify-%@loadtest.invalid'
UNION ALL
SELECT 'fake_benefits', COUNT(*) FROM new_benefit WHERE created_at = '2000-01-01 00:00:00'
UNION ALL
SELECT 'signup_verifications', COUNT(*) FROM email_verification WHERE email LIKE 'notify-mail-%@loadtest.invalid';
