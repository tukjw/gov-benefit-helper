-- 회원 계정(#38). email은 소문자로 바꿔 저장한다. 정렬 규칙(결정 0001)도 대소문자를 무시하므로 unique 제약이 대소문자만 다른 이메일을 막는다.
-- password_hash는 접두사를 포함한 BCrypt 값이다(결정 0013).
CREATE TABLE user_account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_account_email UNIQUE (email)
);
