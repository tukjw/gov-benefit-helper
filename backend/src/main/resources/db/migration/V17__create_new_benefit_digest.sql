-- 새 혜택 알림 묶음(#130, REQ-NOTIFY-03). 사용자별로 그날 보낼 새 혜택을 메일 한 통으로 묶는다. 사용자·묶은 날짜당 하나다.
-- status: PENDING(발송 대기). 탈퇴하면 함께 지워진다(DEC-AUTH).
CREATE TABLE new_benefit_digest (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    digest_date DATE        NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_new_benefit_digest_user_date UNIQUE (user_id, digest_date),
    CONSTRAINT fk_new_benefit_digest_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
);

-- 알림 항목을 넣은 묶음. 비어 있으면 아직 묶지 않았다. 묶음이 지워지면 항목도 지워진다.
ALTER TABLE new_benefit_target
    ADD COLUMN digest_id BIGINT NULL AFTER benefit_id,
    ADD CONSTRAINT fk_new_benefit_target_digest
        FOREIGN KEY (digest_id) REFERENCES new_benefit_digest (id) ON DELETE CASCADE;
