-- 새 혜택 알림 대상을 고른 시각(#117). 비어 있으면 아직 고르지 않았다. 판단 가능(READY)인 행만 채운다.
ALTER TABLE new_benefit
    ADD COLUMN targets_selected_at DATETIME(6) NULL AFTER status;

-- 새 혜택 알림 대상(#117, REQ-NOTIFY-03). 알림을 켰고 선정할 때 조건 비교 결과가 '해당'인 사용자와 새 혜택의 조합당 한 행이다.
-- 탈퇴하면 함께 지워진다(DEC-AUTH).
CREATE TABLE new_benefit_target (
    user_id    BIGINT NOT NULL,
    benefit_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, benefit_id),
    CONSTRAINT fk_new_benefit_target_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_new_benefit_target_new_benefit FOREIGN KEY (benefit_id) REFERENCES new_benefit (benefit_id)
);
