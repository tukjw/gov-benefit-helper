-- 발송 후 auth.mail.window(24시간)가 지난 인증 기록을 하루 한 번 지운다(#109, 결정 0041).
-- V6의 "행을 지우지 않는다"는 결정 0019 기준이며 0041로 바뀌었다. 삭제가 지울 범위만 잠그도록 created_at 인덱스를 둔다.
CREATE INDEX idx_email_verification_created ON email_verification (created_at);
