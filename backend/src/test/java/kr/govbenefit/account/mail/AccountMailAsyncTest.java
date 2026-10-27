package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영과 같은 비동기 실행기({@code auth.mail.async=true})에서는 요청의 응답이 메일 발송을 기다리지 않는다(결정 0032).
 * 재설정 요청의 응답 시간으로 가입 여부가 드러나지 않게 하기 위해서다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "auth.mail.async=true", "auth.mail.sender=resend",
        "auth.mail.resend.api-key=re_test_81b0e4", "auth.mail.resend.from=no-reply@govbenefit.kr"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountMailAsyncTest {

    private static final String EMAIL = "alice@example.com";
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(5);

    private static final ResendStubServer stub = ResendStubServer.start();

    @DynamicPropertySource
    static void resendProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.mail.resend.base-url", stub::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM email_verification");
        stub.reset();
        signUp();
        stub.reset();
    }

    @Test
    void 재설정_요청의_응답이_메일_발송을_기다리지_않는다() throws Exception {
        // stub이 응답을 붙잡는다. 발송이 요청 스레드에서 일어나면 붙잡힌 10초 동안 요청이 끝나지 않는다.
        stub.hold();
        try {
            mockMvc.perform(post("/api/auth/password-reset")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(Map.of("email", EMAIL))))
                    .andExpect(status().isNoContent());

            // 응답을 받은 시점에 발송은 시작됐지만 아직 끝나지 않았다. 동기였다면 붙잡힌 시간이 지나 응답한 뒤라 0이다.
            assertThat(stub.awaitRequest(ARRIVAL_TIMEOUT)).isTrue();
            assertThat(stub.held()).isEqualTo(1);
            assertThat(stub.last().to()).isEqualTo(EMAIL);
            assertThat(stub.last().resetUrl()).contains("#token=");
        } finally {
            stub.release();
        }
    }

    /**
     * 준비 단계라 인증번호 메일은 stub에서 도착을 기다려 읽는다.
     */
    private void signUp() throws Exception {
        SignUpSteps steps = new SignUpSteps(mockMvc, jsonMapper, null);
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        assertThat(stub.awaitRequest(ARRIVAL_TIMEOUT)).isTrue();
        String response = steps.confirm(EMAIL, stub.last().signupCode())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String ticket = jsonMapper.readTree(response).get("verificationToken").asString();
        steps.signUp(EMAIL, "alice-password", ticket).andExpect(status().isCreated());
    }
}
