package kr.govbenefit.account.mail;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 메일을 보내지 않고 내용을 로그로 남긴다. 인증번호와 재설정 링크가 로그에 그대로 남으므로 local에서만 쓴다(결정 0047).
 */
@Slf4j
public class LoggingAccountMailSender implements AccountMailSender {

    @Override
    public void sendSignupCode(String email, String code, Duration validFor) {
        log.info("[메일 미발송] 가입 인증번호 to={} code={} validFor={}", email, code, validFor);
    }

    @Override
    public void sendPasswordReset(String email, String resetUrl, Duration validFor) {
        log.info("[메일 미발송] 비밀번호 재설정 to={} url={} validFor={}", email, resetUrl, validFor);
    }

    @Override
    public void sendNewBenefitDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl) {
        log.info("[메일 미발송] 새 혜택 알림 to={} benefits={}", email, benefits);
    }
}
