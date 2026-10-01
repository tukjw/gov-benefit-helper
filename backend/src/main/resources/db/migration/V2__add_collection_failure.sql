-- 실행을 FAILED로 만들거나 중단시킨 원인. 끝까지 돈 실행은 NULL. 값은 CollectionErrorType(결정 0006)
ALTER TABLE collection_run ADD COLUMN error_type VARCHAR(30) NULL AFTER status;

-- 수집 실행에서 실패한 쪽. attempts는 그 쪽에 보낸 HTTP 시도 횟수
CREATE TABLE collection_failure (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    run_id     BIGINT        NOT NULL,
    endpoint   VARCHAR(30)   NOT NULL,
    page       INT           NOT NULL,
    error_type VARCHAR(30)   NOT NULL,
    attempts   INT           NOT NULL,
    message    VARCHAR(1000) NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_collection_failure_run FOREIGN KEY (run_id) REFERENCES collection_run (id)
);
