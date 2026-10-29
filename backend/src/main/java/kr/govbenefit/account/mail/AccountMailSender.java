package kr.govbenefit.account.mail;

import java.time.Duration;

/**
 * 계정 메일 발송(결정 0032). 실제 발송은 {@link ResendAccountMailSender}가 하고, 테스트는 가짜 구현으로 검증한다.
 * 메일 종류마다 메서드를 두어 구현이 제목·본문을 정하고, 가짜 구현은 번호와 링크를 그대로 기록한다.
 */
public interface AccountMailSender {

    /**
     * 가입 전 이메일 인증번호
     *
     * @param validFor 번호를 쓸 수 있는 시간. 본문 안내에 쓴다.
     */
    void sendSignupCode(String email, String code, Duration validFor);

    /**
     * 비밀번호 재설정 링크
     *
     * @param validFor 링크를 쓸 수 있는 시간. 본문 안내에 쓴다.
     */
    void sendPasswordReset(String email, String resetUrl, Duration validFor);
}
