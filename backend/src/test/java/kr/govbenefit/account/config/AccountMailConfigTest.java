package kr.govbenefit.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import kr.govbenefit.account.mail.SesAccountMailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * 발송 구현은 설정으로만 고른다(결정 0045). 기본 설정에는 구현이 없어 기동에 실패하고, SES를 고르면 접근 키,
 * 리전, 발신 주소가 모두 있어야 뜬다.
 */
class AccountMailConfigTest {

    private static final String[] SES = {"auth.mail.sender=ses", "auth.mail.ses.connect-timeout=5s",
            "auth.mail.ses.read-timeout=10s", "auth.mail.ses.access-key-id=AKIATESTCONFIG0B5C2A",
            "auth.mail.ses.secret-access-key=test/Config+Secret0b5c2a", "auth.mail.ses.region=ap-northeast-2",
            "auth.mail.ses.from=no-reply@govbenefit.kr"};

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
    void ses면_SES_구현을_쓰고_컨텍스트와_함께_닫는다() {
        runner.withPropertyValues(SES)
                .run(context -> assertThat(context).hasNotFailed().getBean(AccountMailSender.class)
                        .isInstanceOf(SesAccountMailSender.class));
    }

    @ParameterizedTest
    @CsvSource({"access-key-id, SES_ACCESS_KEY_ID", "secret-access-key, SES_SECRET_ACCESS_KEY",
            "region, SES_REGION", "from, SES_FROM"})
    void ses인데_값이_하나라도_비면_빠진_변수_이름으로_기동에_실패한다(String property, String variable) {
        runner.withPropertyValues(SES)
                .withPropertyValues("auth.mail.ses." + property + "=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining(variable));
    }

    @Test
    void 기본_실행기는_가상_스레드에서_비동기로_보낸다() {
        runner.run(context -> {
            TaskExecutor executor = context.getBean(AccountMailConfig.EXECUTOR, TaskExecutor.class);
            assertThat(executor).isInstanceOf(SimpleAsyncTaskExecutor.class);
            Thread[] thread = new Thread[1];
            ((SimpleAsyncTaskExecutor) executor).submit(() -> thread[0] = Thread.currentThread()).get();
            assertThat(thread[0].isVirtual()).isTrue();
            assertThat(thread[0].getName()).startsWith("account-mail-");
        });
    }

    @Test
    void async가_false면_호출한_스레드에서_보낸다() {
        runner.withPropertyValues("auth.mail.async=false")
                .run(context -> assertThat(context.getBean(AccountMailConfig.EXECUTOR, TaskExecutor.class))
                        .isInstanceOf(SyncTaskExecutor.class));
    }
}
