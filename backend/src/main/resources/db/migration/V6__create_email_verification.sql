-- 가입 전 이메일 인증(#47, 결정 0019). 발송 한 번에 한 행이며 같은 이메일의 가장 최근 행만 유효하다.
-- 인증번호와 가입 티켓은 SHA-256(소문자 hex)만 둔다. 계정이 생기기 전이라 FK가 없고, 행을 지우지 않는다.
-- attempts는 인증번호를 비교한 횟수다. 비교하기 전에 늘려 동시 요청도 제한을 넘지 못한다.
CREATE TABLE email_verification (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(254) NOT NULL,
    code_hash         CHAR(64)     NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    verified_at       DATETIME(6)  NULL,
    ticket_hash       CHAR(64)     NULL,
    ticket_expires_at DATETIME(6)  NULL,
    ticket_used_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_email_verification_ticket_hash UNIQUE (ticket_hash),
    INDEX idx_email_verification_email_created (email, created_at)
);
