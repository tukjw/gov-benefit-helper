package kr.govbenefit.account.mail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.govbenefit.account.domain.Emails;
import org.springframework.stereotype.Component;

/**
 * 테스트용 가짜 발송(결정 0020). 보내지 않고 기록만 한다. 테스트 클래스패스의 컴포넌트 스캔으로 모든 {@code @SpringBootTest}
 * 컨텍스트에 들어간다. 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #clear()}한다.
 */
@Component
public class RecordingAccountMailSender implements AccountMailSender {

    public enum Kind { SIGNUP_CODE, PASSWORD_RESET }

    /**
     * @param content 인증번호 또는 재설정 링크
     */
    public record SentMail(Kind kind, String email, String content, Duration validFor) {
    }

    private final List<SentMail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendSignupCode(String email, String code, Duration validFor) {
        sent.add(new SentMail(Kind.SIGNUP_CODE, email, code, validFor));
    }

    @Override
    public void sendPasswordReset(String email, String resetUrl, Duration validFor) {
        sent.add(new SentMail(Kind.PASSWORD_RESET, email, resetUrl, validFor));
    }

    public List<SentMail> sent() {
        return List.copyOf(sent);
    }

    public List<SentMail> sent(Kind kind, String email) {
        String normalized = Emails.normalize(email);
        return sent.stream().filter(mail -> mail.kind() == kind && mail.email().equals(normalized)).toList();
    }

    public String lastSignupCode(String email) {
        return last(Kind.SIGNUP_CODE, email).content();
    }

    public String lastResetUrl(String email) {
        return last(Kind.PASSWORD_RESET, email).content();
    }

    /** 재설정 링크의 {@code #token=} 뒤의 값 */
    public String lastResetToken(String email) {
        String url = lastResetUrl(email);
        return url.substring(url.indexOf("#token=") + "#token=".length());
    }

    public void clear() {
        sent.clear();
    }

    private SentMail last(Kind kind, String email) {
        List<SentMail> mails = sent(kind, email);
        if (mails.isEmpty()) {
            throw new AssertionError(kind + " 메일을 " + email + "로 보낸 적이 없습니다. 보낸 메일: " + sent);
        }
        return mails.getLast();
    }
}
