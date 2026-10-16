-- 비밀번호 재설정 토큰(#47, 결정 0019). 발송 한 번에 한 행이며 같은 계정의 가장 최근 행만 유효하다.
-- 원문은 저장하지 않고 SHA-256(소문자 hex)만 둔다. 탈퇴하면 계정과 함께 지운다.
CREATE TABLE password_reset_token (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX idx_password_reset_token_user_created (user_id, created_at)
);
