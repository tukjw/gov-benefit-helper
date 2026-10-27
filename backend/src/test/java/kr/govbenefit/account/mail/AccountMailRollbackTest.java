package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.RecordingAccountMailSender.Kind;
import kr.govbenefit.account.service.EmailVerificationService;
import kr.govbenefit.account.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커밋된 발급만 메일로 보낸다(결정 0032). 발급한 요청이 롤백되면 인증번호와 재설정 링크가 나가지 않는다.
 * 서비스 호출을 바깥 트랜잭션으로 감싸 롤백시킨다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountMailRollbackTest {

    private static final String EMAIL = "alice@example.com";

    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private PasswordResetService passwordResetService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RecordingAccountMailSender mailSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void 롤백된_요청의_인증번호는_발송되지_않는다() {
        transaction.executeWithoutResult(status -> {
            emailVerificationService.request(EMAIL);
            status.setRollbackOnly();
        });

        assertThat(mailSender.sent()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_verification", Integer.class)).isZero();

        transaction.executeWithoutResult(status -> emailVerificationService.request(EMAIL));

        assertThat(mailSender.sent(Kind.SIGNUP_CODE, EMAIL)).hasSize(1);
    }

    @Test
    void 롤백된_요청의_재설정_링크는_발송되지_않는다() throws Exception {
        new SignUpSteps(mockMvc, jsonMapper, mailSender).signUp(EMAIL, "alice-password");
        mailSender.clear();

        transaction.executeWithoutResult(status -> {
            passwordResetService.request(EMAIL);
            status.setRollbackOnly();
        });

        assertThat(mailSender.sent()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM password_reset_token", Integer.class)).isZero();

        transaction.executeWithoutResult(status -> passwordResetService.request(EMAIL));

        assertThat(mailSender.sent(Kind.PASSWORD_RESET, EMAIL)).hasSize(1);
    }
}
