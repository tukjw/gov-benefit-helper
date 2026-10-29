-- 리프레시 토큰(#45, 결정 0017). 원문은 저장하지 않고 SHA-256 해시(소문자 hex)만 둔다.
-- family_id는 한 번의 로그인에서 교체로 이어진 토큰을 묶는다. 계정을 지우면 함께 지운다(DEC-AUTH 탈퇴).
CREATE TABLE refresh_token (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    family_id  CHAR(36)    NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    issued_at  DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    rotated_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX idx_refresh_token_family (family_id)
);
