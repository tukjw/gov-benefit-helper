-- 남은 부하 테스트 계정과 그 계정의 리프레시 토큰 수. 측정이 끝난 뒤 둘 다 0이어야 한다.
SELECT 'accounts', COUNT(*) FROM user_account WHERE email LIKE 'loadtest-%@loadtest.invalid'
UNION ALL
SELECT 'refresh_tokens', COUNT(*)
FROM refresh_token rt
JOIN user_account u ON u.id = rt.user_id
WHERE u.email LIKE 'loadtest-%@loadtest.invalid';
