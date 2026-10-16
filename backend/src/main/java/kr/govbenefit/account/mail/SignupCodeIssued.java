package kr.govbenefit.account.mail;

import java.time.Duration;

/**
 * 가입 인증번호를 발급했다. 트랜잭션이 커밋된 뒤 메일로 보낸다({@link AccountMailListener}).
 *
 * @param verificationId 발송 행 ID. 발송 실패 로그에 이메일 대신 남긴다.
 */
public record SignupCodeIssued(Long verificationId, String email, String code, Duration validFor) {

    /** 로그에 남아도 번호가 드러나지 않게 한다. */
    @Override
    public String toString() {
        return "SignupCodeIssued[verificationId=" + verificationId + "]";
    }
}
