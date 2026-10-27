package kr.govbenefit.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import kr.govbenefit.account.mail.ResendAccountMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 발송 구현은 설정으로만 고른다(결정 0032). 기본 설정에는 구현이 없어 기동에 실패하고, Resend를 고르면 API 키와
 * 발신 주소가 있어야 뜬다.
 */
class AccountMailConfigTest {

    private static final String[] RESEND = {"auth.mail.sender=resend",
            "auth.mail.resend.base-url=https://api.resend.com", "auth.mail.resend.connect-timeout=5s",
            "auth.mail.resend.read-timeout=10s"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccountMailConfig.class);

    @Test
    void 지정하지_않으면_발송_구현이_없다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AccountMailSender.class));
        // application.yaml의 ${MAIL_SENDER:}는 값이 없으면 빈 문자열이다.
        runner.withPropertyValues("auth.mail.sender=")
                .run(context -> assertThat(context).doesNotHaveBean(AccountMailSender.class));
    }

    @Test
    void logging이면_로그_구현을_쓴다() {
        runner.withPropertyValues("auth.mail.sender=logging")
                .run(context -> assertThat(context).getBean(AccountMailSender.class)
                        .isInstanceOf(LoggingAccountMailSender.class));
    }

    @Test
    void resend면_Resend_구현을_쓴다() {
        runner.withPropertyValues(RESEND)
                .withPropertyValues("auth.mail.resend.api-key=re_test_0b5c2a",
                        "auth.mail.resend.from=no-reply@govbenefit.kr")
                .run(context -> assertThat(context).getBean(AccountMailSender.class)
                        .isInstanceOf(ResendAccountMailSender.class));
    }

    @Test
    void resend인데_API_키가_없으면_기동에_실패한다() {
        runner.withPropertyValues(RESEND)
                .withPropertyValues("auth.mail.resend.api-key=", "auth.mail.resend.from=no-reply@govbenefit.kr")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("RESEND_API_KEY"));
    }

    @Test
    void resend인데_발신_주소가_없으면_기동에_실패한다() {
        runner.withPropertyValues(RESEND)
                .withPropertyValues("auth.mail.resend.api-key=re_test_0b5c2a")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("RESEND_FROM"));
    }
}
