-- 목록 원문을 해석한 값(결정 0008). 근거 원문은 source_record(service_id, SERVICE_LIST)다.
-- 출처 시각은 원문 등록일시·수정일시이며, 없거나 해석할 수 없으면 NULL이다. 수집 시각으로 채우지 않는다.
-- 신청기한 값은 apply_period_text 원문을 분류한 결과다. 기존 행은 다음 수집에서 채워진다.
ALTER TABLE benefit
    ADD COLUMN source_registered_at      DATETIME    NULL AFTER detail_url,
    ADD COLUMN source_modified_at        DATETIME    NULL AFTER source_registered_at,
    ADD COLUMN apply_period_type         VARCHAR(20) NULL AFTER source_modified_at,
    ADD COLUMN apply_period_rule         VARCHAR(30) NULL AFTER apply_period_type,
    ADD COLUMN apply_period_needs_review BOOLEAN     NULL AFTER apply_period_rule;
