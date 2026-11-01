-- 부하 테스트 계정을 지운다(결정 0037). refresh_token·password_reset_token·user_profile·bookmark는
-- user_account를 ON DELETE CASCADE로 참조해 함께 지워진다. 가입을 거치지 않아 email_verification 행은 없다.
DELETE FROM user_account WHERE email LIKE 'loadtest-%@loadtest.invalid';
