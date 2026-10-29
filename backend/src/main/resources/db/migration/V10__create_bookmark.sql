-- 북마크(#56, REQ-SAVE-01의 관심 목록). 사용자·혜택 조합당 한 행이다. bookmarked_at은 마지막으로 저장한 시각이다.
-- benefit_id는 RESTRICT라 북마크가 달린 혜택 행은 지울 수 없다(결정 0023).
CREATE TABLE bookmark (
    user_id       BIGINT      NOT NULL,
    benefit_id    BIGINT      NOT NULL,
    bookmarked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, benefit_id),
    INDEX idx_bookmark_user_bookmarked (user_id, bookmarked_at, benefit_id),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_benefit FOREIGN KEY (benefit_id) REFERENCES benefit (id)
);
