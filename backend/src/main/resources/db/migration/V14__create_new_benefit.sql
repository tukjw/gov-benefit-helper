-- 수집에서 처음 저장된 혜택(결정 0042). 목록 경로를 끝까지 성공한 실행이 생긴 뒤에 만든 혜택만 있다.
-- status: WAITING_CONDITIONS(지원조건 원문을 아직 받지 않음), READY(대상 판단 가능)
CREATE TABLE new_benefit (
    benefit_id      BIGINT      NOT NULL,
    detected_run_id BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (benefit_id),
    CONSTRAINT fk_new_benefit_benefit FOREIGN KEY (benefit_id) REFERENCES benefit (id),
    CONSTRAINT fk_new_benefit_run FOREIGN KEY (detected_run_id) REFERENCES collection_run (id)
);
