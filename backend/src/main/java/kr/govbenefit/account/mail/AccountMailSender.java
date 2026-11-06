package kr.govbenefit.account.mail;

import java.time.Duration;
import java.util.List;

/**
 * 회원에게 보내는 메일(계정 메일, 새 혜택 알림) 발송(결정 0045). 실제 발송은 {@link SesAccountMailSender}가 하고, 테스트는 가짜
 * 구현으로 검증한다. 메일 종류마다 메서드를 두어 구현이 제목·본문을 정하고, 가짜 구현은 번호와 링크를 그대로 기록한다.
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

    /**
     * 조건에 맞는 새 혜택 알림. 메일 한 통에 혜택을 받은 순서대로 적는다.
     *
     * @param siteUrl 알림 끄기 안내에 적는 웹 주소
     */
    void sendNewBenefitDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl);
}
