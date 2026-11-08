package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.SignUpSteps;
import kr.govbenefit.account.mail.SesStubServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * {@code auth.mail.sender=ses}로 띄우면 계정 메일이 Amazon SES API로 요청된다(결정 0047). 실제 SES 대신 로컬 stub
 * 서버가 받는다. 발송 실패는 응답을 바꾸지 않고, 다시 보내지 않으며, 로그에는 행 ID만 남는다. 실행기는 테스트 공통 설정의
 * 동기다.
 *
 * <p>이 클래스만 쓰는 설정이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 바로 닫아({@link DirtiesContext}) 컨텍스트마다
 * 뜨는 MySQL 컨테이너가 CI 러너의 메모리에 쌓이지 않게 한다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "auth.mail.sender=ses",
        "auth.mail.ses.access-key-id=" + SesMailDeliveryTest.ACCESS_KEY_ID,
        "auth.mail.ses.secret-access-key=" + SesMailDeliveryTest.SECRET_ACCESS_KEY,
        "auth.mail.ses.region=ap-northeast-2", "auth.mail.ses.from=" + SesMailDeliveryTest.FROM})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class SesMailDeliveryTest {

    static final String ACCESS_KEY_ID = "AKIATESTDELIVERY3A9D";
    static final String SECRET_ACCESS_KEY = "test/Delivery+Secret3a9d2f";
    static final String FROM = "정부 혜택 도우미 <no-reply@govbenefit.kr>";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "alice-password";
    private static final String NEW_PASSWORD = "new-alice-password";

    private static final SesStubServer stub = SesStubServer.start();

    @DynamicPropertySource
    static void sesProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.mail.ses.endpoint", stub::endpoint);
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
    void 가입_인증번호_메일이_SES로_요청되고_그_번호로_확인된다() throws Exception {
        steps.requestCode("ALICE@example.com").andExpect(status().isNoContent());

        ReceivedRequest request = stub.last();
        assertThat(stub.received()).hasSize(1);
        assertThat(request.path()).isEqualTo(SesStubServer.SEND_EMAIL_PATH);
        assertThat(request.authorization()).startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY_ID + "/");
        assertThat(request.from()).isEqualTo(FROM);
        assertThat(request.to()).isEqualTo(EMAIL);
        steps.confirm(EMAIL, request.signupCode()).andExpect(status().isOk());
    }

    @Test
    void 재설정_메일이_SES로_요청되고_그_링크로_재설정된다() throws Exception {
        signUp();

        requestReset(EMAIL).andExpect(status().isNoContent());

        ReceivedRequest request = stub.last();
        assertThat(request.to()).isEqualTo(EMAIL);
        assertThat(request.resetUrl()).startsWith("https://govbenefit.kr/reset-password#token=");
        reset(request.resetToken(), NEW_PASSWORD).andExpect(status().isNoContent());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
            // 샌드박스에서 인증하지 않은 수신자로 보내면 설명에 수신 주소가 들어간다.
            "400, MessageRejected, Email address is not verified. The following identities failed the check: "
                    + "bob@example.com " + EMAIL,
            "429, TooManyRequestsException, Maximum sending rate exceeded. " + EMAIL,
            "500, InternalFailure, Internal failure for " + EMAIL})
    void SES가_오류를_응답해도_응답은_같고_한_번만_요청되며_로그에_민감한_값이_없다(int status, String errorType,
                                                                    String message, CapturedOutput output)
            throws Exception {
        signUp();
        int before = stub.received().size();
        stub.respondWithError(status, errorType, message);

        steps.requestCode("bob@example.com").andExpect(status().isNoContent());
        requestReset(EMAIL).andExpect(status().isNoContent());

        assertThat(stub.received()).hasSize(before + 2);
        assertThat(output).contains("SES 응답 상태 " + status + " " + errorType);
        assertFailureLoggedWithoutSecrets(output);
    }

    @Test
    void SES와_연결이_끊겨도_응답은_같고_한_번만_요청되며_로그에_민감한_값이_없다(CapturedOutput output) throws Exception {
        signUp();
        int before = stub.received().size();
        stub.disconnect();

        steps.requestCode("bob@example.com").andExpect(status().isNoContent());
        requestReset(EMAIL).andExpect(status().isNoContent());

        assertThat(stub.received()).hasSize(before + 2);
        assertThat(output).contains("SES 연결 실패: ");
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
                reset.resetToken(), ACCESS_KEY_ID, SECRET_ACCESS_KEY);
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
