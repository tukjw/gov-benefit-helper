-- 수집 실행. status: RUNNING, SUCCEEDED, FAILED
CREATE TABLE collection_run (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    started_at    DATETIME(6)   NOT NULL,
    finished_at   DATETIME(6)   NULL,
    status        VARCHAR(20)   NOT NULL,
    error_message VARCHAR(1000) NULL,
    PRIMARY KEY (id)
);

-- 수집 실행의 경로별 진행. endpoint: SERVICE_LIST, SERVICE_DETAIL, SUPPORT_CONDITIONS
CREATE TABLE collection_run_step (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    run_id            BIGINT      NOT NULL,
    endpoint          VARCHAR(30) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    started_at        DATETIME(6) NOT NULL,
    finished_at       DATETIME(6) NULL,
    page_count        INT         NOT NULL,
    received_count    INT         NOT NULL,
    created_count     INT         NOT NULL,
    changed_count     INT         NOT NULL,
    unchanged_count   INT         NOT NULL,
    quarantined_count INT         NOT NULL,
    unlinked_count    INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collection_run_step_run_endpoint UNIQUE (run_id, endpoint),
    CONSTRAINT fk_collection_run_step_run FOREIGN KEY (run_id) REFERENCES collection_run (id)
);

-- 필수 식별자가 없어 저장하지 않은 레코드
CREATE TABLE collection_quarantine (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    run_id     BIGINT      NOT NULL,
    endpoint   VARCHAR(30) NOT NULL,
    page       INT         NOT NULL,
    reason     VARCHAR(50) NOT NULL,
    payload    JSON        NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_collection_quarantine_run FOREIGN KEY (run_id) REFERENCES collection_run (id)
);

-- 서비스ID·경로별 최신 원문. content_hash는 조회수를 뺀 내용의 SHA-256이다.
-- 목록에 없는 ID의 원문도 남기도록 benefit에 FK를 두지 않는다.
CREATE TABLE source_record (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    service_id         VARCHAR(20) NOT NULL,
    endpoint           VARCHAR(30) NOT NULL,
    payload            JSON        NOT NULL,
    content_hash       CHAR(64)    NOT NULL,
    collected_at       DATETIME(6) NOT NULL,
    content_changed_at DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_source_record_service_endpoint UNIQUE (service_id, endpoint)
);

-- 내용이 바뀌어 교체된 이전 원문
CREATE TABLE source_record_history (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    service_id         VARCHAR(20) NOT NULL,
    endpoint           VARCHAR(30) NOT NULL,
    payload            JSON        NOT NULL,
    content_hash       CHAR(64)    NOT NULL,
    collected_at       DATETIME(6) NOT NULL,
    replaced_at        DATETIME(6) NOT NULL,
    replaced_by_run_id BIGINT      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_source_record_history_service_endpoint (service_id, endpoint),
    CONSTRAINT fk_source_record_history_run FOREIGN KEY (replaced_by_run_id) REFERENCES collection_run (id)
);

-- 목록(serviceList) 기준 혜택. 값은 원문 문자열 그대로 둔다.
CREATE TABLE benefit (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    service_id         VARCHAR(20)   NOT NULL,
    name               VARCHAR(255)  NULL,
    purpose_summary    VARCHAR(500)  NULL,
    service_field      VARCHAR(50)   NULL,
    user_type          VARCHAR(100)  NULL,
    support_type       VARCHAR(255)  NULL,
    target             TEXT          NULL,
    selection_criteria TEXT          NULL,
    support_content    TEXT          NULL,
    apply_period_text  VARCHAR(1000) NULL,
    apply_method_type  VARCHAR(255)  NULL,
    phone_inquiry      TEXT          NULL,
    reception_agency   VARCHAR(255)  NULL,
    agency_name        VARCHAR(100)  NULL,
    agency_type        VARCHAR(50)   NULL,
    agency_code        VARCHAR(20)   NULL,
    department_name    VARCHAR(100)  NULL,
    detail_url         VARCHAR(500)  NULL,
    last_seen_at       DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_benefit_service_id UNIQUE (service_id)
);

-- 상세(serviceDetail) 항목. 목록과 값이 같은 필드는 benefit에만 둔다. 행이 없으면 상세 미수집이다.
CREATE TABLE benefit_detail (
    benefit_id               BIGINT       NOT NULL,
    purpose                  TEXT         NULL,
    apply_method             TEXT         NULL,
    required_documents       TEXT         NULL,
    official_check_documents TEXT         NULL,
    self_check_documents     TEXT         NULL,
    contact                  TEXT         NULL,
    online_apply_url         VARCHAR(500) NULL,
    laws                     TEXT         NULL,
    local_laws               TEXT         NULL,
    admin_rules              TEXT         NULL,
    reception_agency_name    VARCHAR(255) NULL,
    support_type             VARCHAR(255) NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (benefit_id),
    CONSTRAINT fk_benefit_detail_benefit FOREIGN KEY (benefit_id) REFERENCES benefit (id)
);
