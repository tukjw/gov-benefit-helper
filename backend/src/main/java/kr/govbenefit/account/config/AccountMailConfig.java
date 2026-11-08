package kr.govbenefit.account.config;

import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.LoggingAccountMailSender;
import kr.govbenefit.account.mail.SesAccountMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 메일 발송 구현을 {@code auth.mail.sender}로 고르고, 커밋 뒤 발송을 실행할 실행기를 둔다(결정 0047).
 * 지정하지 않으면 {@link AccountMailSender} 빈이 없어 기동에 실패한다. 설정 실수 하나로 운영에서 인증번호가 로그로
 * 새거나 메일 없이 뜨지 않게 하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(SesProperties.class)
public class AccountMailConfig {

    /** {@code AccountMailListener}가 {@code @Async}로 가리키는 실행기 이름 */
    public static final String EXECUTOR = "accountMailExecutor";

    private static final long TERMINATION_TIMEOUT_MILLIS = 10_000;

    /**
     * 메일을 보내지 않고 인증번호와 재설정 링크를 로그로 남긴다. local 프로필에서만 켠다.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "logging")
    AccountMailSender loggingAccountMailSender() {
        return new LoggingAccountMailSender();
    }

    /**
     * Amazon SES로 보낸다. 접근 키, 리전, 발신 주소 중 하나라도 없으면 기동에 실패한다. 클라이언트는 컨텍스트가 닫힐 때
     * {@link SesAccountMailSender#close()}로 닫는다.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.mail.sender", havingValue = "ses")
    AccountMailSender sesAccountMailSender(SesProperties properties) {
        require(properties.accessKeyId(), "SES 접근 키 ID(SES_ACCESS_KEY_ID)가");
        require(properties.secretAccessKey(), "SES 비밀 키(SES_SECRET_ACCESS_KEY)가");
        require(properties.region(), "SES 리전(SES_REGION)이");
        require(properties.from(), "메일 발신 주소(SES_FROM)가");
        return new SesAccountMailSender(properties);
    }

    /**
     * 요청 스레드가 발송을 기다리지 않도록 가상 스레드에서 보낸다. 큐가 없어 몰려도 거절하거나 요청 스레드에서 실행하지
     * 않는다. 종료할 때는 진행 중인 발송을 잠시 기다린다. {@code auth.mail.async=false}(테스트)면 호출한 스레드에서 바로
     * 보내, 요청이 끝난 직후 보낸 메일을 확인할 수 있다.
     */
    @Bean(EXECUTOR)
    TaskExecutor accountMailExecutor(@Value("${auth.mail.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("account-mail-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    /**
     * @param subject 조사까지 붙인 설정 이름. 예: {@code "SES 리전(SES_REGION)이"}
     */
    private static void require(String value, String subject) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(subject + " 설정되지 않았습니다.");
        }
    }
}
