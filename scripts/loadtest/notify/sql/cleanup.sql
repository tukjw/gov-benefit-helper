-- 알림 발송 장애 측정(#137)의 합성 데이터를 지운다. 계정을 지우면 묶음과 알림 항목이 ON DELETE CASCADE로 함께 지워진다.
-- 가짜 새 혜택은 알림 항목이 참조하는 동안 지울 수 없으므로(FK에 CASCADE가 없다) 계정 다음에 지운다. 다른 알림 항목이
-- 참조하면 지우지 않고 count.sql에 남은 수로 나온다.
-- 계정 메일 시나리오의 인증 기록은 계정이 없어 따로 지운다.
DELETE FROM user_account WHERE email LIKE 'notify-%@loadtest.invalid';
DELETE nb FROM new_benefit nb
LEFT JOIN new_benefit_target t ON t.benefit_id = nb.benefit_id
WHERE nb.created_at = '2000-01-01 00:00:00' AND t.benefit_id IS NULL;
DELETE FROM email_verification WHERE email LIKE 'notify-mail-%@loadtest.invalid';
