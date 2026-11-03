-- 건강 관련 생애 속성의 별도 동의(#110, DEC-PROFILE, 개인정보 보호법 제23조). 처음 동의한 시각이며 NULL이면 동의 기록이 없다.
-- 4개 속성을 모두 빼고 저장하면 비우고, 탈퇴하면 행과 함께 지워진다.
ALTER TABLE user_profile
    ADD COLUMN sensitive_attribute_consented_at DATETIME(6) NULL AFTER sigungu_code;

-- 동의 기록 없이 저장된 장애인(JA0328)·질병/질환자(JA0330)·임산부(JA0302)·예비부모/난임(JA0301)을 지운다.
DELETE FROM user_profile_attribute WHERE condition_code IN ('JA0301', 'JA0302', 'JA0328', 'JA0330');
