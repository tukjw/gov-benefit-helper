package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.ResendStubServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code auth.mail.sender=resend}로 띄우면 계정 메일이 Resend API로 요청된다(결정 0032). 실제 Resend 대신 로컬 stub
 * 서버가 받는다. 발송 실패는 응답을 바꾸지 않고, 로그에는 행 ID만 남는다. 실행기는 테스트 공통 설정의 동기다.
 *
 * <p>이 클래스만 쓰는 설정이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 바로 닫아({@link DirtiesContext}) 컨텍스트마다
 * 뜨는 MySQL 컨테이너가 CI 러너의 메모리에 쌓이지 않게 한다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "auth.mail.sender=resend",
        "auth.mail.resend.api-key=" + ResendMailDeliveryTest.API_KEY,
        "auth.mail.resend.from=" + ResendMailDeliveryTest.FROM})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class ResendMailDeliveryTest {

    static final String API_KEY = "re_test_3a9d2f";
    static final String FROM = "정부 혜택 도우미 <no-reply@govbenefit.kr>";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "alice-password";
    private static final String NEW_PASSWORD = "new-alice-password";

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
    private JsonMapper jsonMapper;

    private SignUpSteps steps;

    @BeforeEach
    void setUp() {
        stub.reset();
        // 가짜 발송이 없으므로 번호는 stub이 받은 본문에서 읽는다. 가짜 발송을 쓰는 메서드는 부르지 않는다.
        steps = new SignUpSteps(mockMvc, jsonMapper, null);
    }

    @Test
    void 가입_인증번호_메일이_Resend로_요청되고_그_번호로_확인된다() throws Exception {
        steps.requestCode("ALICE@example.com").andExpect(status().isNoContent());

        ReceivedRequest request = stub.last();
        assertThat(stub.received()).hasSize(1);
        assertThat(request.path()).isEqualTo("/emails");
        assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
        assertThat(request.body().get("from").asString()).isEqualTo(FROM);
        assertThat(request.to()).isEqualTo(EMAIL);
        steps.confirm(EMAIL, request.signupCode()).andExpect(status().isOk());
    }

    @Test
    void 재설정_메일이_Resend로_요청되고_그_링크로_재설정된다() throws Exception {
        signUp();

        requestReset(EMAIL).andExpect(status().isNoContent());

        ReceivedRequest request = stub.last();
        assertThat(request.to()).isEqualTo(EMAIL);
        assertThat(request.resetUrl()).startsWith("https://govbenefit.kr/reset-password#token=");
        reset(request.resetToken(), NEW_PASSWORD).andExpect(status().isNoContent());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void Resend가_오류를_응답해도_응답은_같고_로그에_민감한_값이_없다(CapturedOutput output) throws Exception {
        signUp();
        // 검증 오류 본문에 보낸 주소가 들어 있어도 로그로 옮겨지지 않아야 한다.
        stub.respondWith(422, "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"" + EMAIL + "\"}");

        steps.requestCode("bob@example.com").andExpect(status().isNoContent());
        stub.respondWith(500, "{\"statusCode\":500,\"name\":\"internal_server_error\",\"message\":\"" + EMAIL + "\"}");
        requestReset(EMAIL).andExpect(status().isNoContent());

        assertFailureLoggedWithoutSecrets(output);
    }

    @Test
    void Resend와_연결이_끊겨도_응답은_같고_로그에_민감한_값이_없다(CapturedOutput output) throws Exception {
        signUp();
        stub.disconnect();

        steps.requestCode("bob@example.com").andExpect(status().isNoContent());
        requestReset(EMAIL).andExpect(status().isNoContent());

        assertFailureLoggedWithoutSecrets(output);
    }

    /**
     * 실패한 두 요청(인증번호, 재설정)이 모두 stub에 도착했고, 실패 로그는 행 ID만 남겼는지 본다.
     */
    private void assertFailureLoggedWithoutSecrets(CapturedOutput output) {
        assertThat(output).contains("가입 인증번호 메일을 보내지 못했습니다. verificationId=")
                .contains("비밀번호 재설정 메일을 보내지 못했습니다. tokenId=");
        // 가입 단계의 인증번호 메일은 성공했으므로 실패한 두 요청은 마지막 둘이다.
        List<ReceivedRequest> received = stub.received();
        ReceivedRequest code = received.get(received.size() - 2);
        ReceivedRequest reset = received.get(received.size() - 1);
        assertThat(output).doesNotContain(EMAIL, "bob@example.com", code.signupCode(), reset.resetUrl(),
                reset.resetToken(), API_KEY);
    }

    private void signUp() throws Exception {
        steps.requestCode(EMAIL).andExpect(status().isNoContent());
        String response = steps.confirm(EMAIL, stub.last().signupCode())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String ticket = jsonMapper.readTree(response).get("verificationToken").asString();
        steps.signUp(EMAIL, PASSWORD, ticket).andExpect(status().isCreated());
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("token", token, "newPassword", newPassword))));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", EMAIL, "password", password))));
    }
}
