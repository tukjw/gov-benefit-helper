-- 새 혜택 알림 발송(#132, REQ-NOTIFY-02, 결정 0048). 묶음마다 발송 시도와 결과를 기록한다.
-- status: PENDING(발송 대기·재시도 대기), ACCEPTED(SES 접수), FAILED(최종 실패), CANCELED(보내기 직전 알림이 꺼져 보내지 않음)
-- attempt_count: SES에 보낸 횟수. next_attempt_at: 이 시각부터 보낸다. last_error: 마지막 발송 실패(상태·오류 코드만).
ALTER TABLE new_benefit_digest
    ADD COLUMN attempt_count   INT          NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at DATETIME(6)  NULL AFTER attempt_count,
    ADD COLUMN last_error      VARCHAR(200) NULL AFTER next_attempt_at,
    ADD COLUMN accepted_at     DATETIME(6)  NULL AFTER last_error;

-- 이미 있는 묶음은 묶은 시각부터 보낸다.
UPDATE new_benefit_digest SET next_attempt_at = created_at;

ALTER TABLE new_benefit_digest
    MODIFY COLUMN next_attempt_at DATETIME(6) NOT NULL,
    ADD INDEX idx_new_benefit_digest_status_next_attempt (status, next_attempt_at);
