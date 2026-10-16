package kr.govbenefit.account.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋된 발급만 메일로 보낸다(결정 0020). 롤백된 요청의 번호나 링크가 메일로 나가지 않는다.
 *
 * <p>발송 실패는 로그만 남기고 응답을 바꾸지 않는다. 이미 커밋된 뒤라 실패로 응답해도 행은 남고, 비밀번호 재설정에서
 * 가입된 이메일만 실패하면 가입 여부가 드러난다. 사용자는 발송 간격이 지난 뒤 다시 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountMailListener {

    private final AccountMailSender sender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SignupCodeIssued event) {
        try {
            sender.sendSignupCode(event.email(), event.code(), event.validFor());
        } catch (RuntimeException ex) {
            log.error("가입 인증번호 메일을 보내지 못했습니다. verificationId={}", event.verificationId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PasswordResetIssued event) {
        try {
            sender.sendPasswordReset(event.email(), event.resetUrl(), event.validFor());
        } catch (RuntimeException ex) {
            log.error("비밀번호 재설정 메일을 보내지 못했습니다. tokenId={}", event.tokenId(), ex);
        }
    }
}
