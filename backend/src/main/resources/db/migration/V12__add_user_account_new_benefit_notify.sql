-- 새 혜택 알림 켜기·끄기(#106, DEC-NOTIFY(새 혜택 알림)). 설정한 적이 없으면 꺼짐이다. 탈퇴하면 계정 행과 함께 지워진다.
ALTER TABLE user_account
    ADD COLUMN new_benefit_notify BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash;
