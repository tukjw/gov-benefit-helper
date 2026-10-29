package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import kr.govbenefit.account.config.ResendProperties;
import kr.govbenefit.account.mail.ResendStubServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resend 요청 형식과 실패 예외(결정 0032). 실제 Resend 대신 로컬 stub 서버로 보낸다.
 */
class ResendAccountMailSenderTest {

    private static final String API_KEY = "re_test_7c1e5b";
    private static final String FROM = "정부 혜택 도우미 <no-reply@govbenefit.kr>";
    private static final String EMAIL = "alice@example.com";
    private static final String CODE = "042917";
    private static final String RESET_URL = "https://govbenefit.kr/reset-password#token=Qm9yZWRUb2tlblZhbHVl";

    private static final ResendStubServer stub = ResendStubServer.start();

    private final ResendAccountMailSender sender = new ResendAccountMailSender(properties(stub.baseUrl()));

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @Test
    void 가입_인증번호를_Resend_형식으로_요청한다() {
        sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10));

        ReceivedRequest request = stub.last();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/emails");
        assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
        assertThat(request.userAgent()).isEqualTo("gov-benefit-helper");
        assertThat(request.body().get("from").asString()).isEqualTo(FROM);
        assertThat(request.body().get("to").size()).isEqualTo(1);
        assertThat(request.to()).isEqualTo(EMAIL);
        assertThat(request.body().get("subject").asString()).isEqualTo("[정부 혜택 도우미] 가입 인증번호");
        assertThat(request.text()).contains("인증번호: " + CODE).contains("10분");
        assertThat(request.body().has("html")).isFalse();
    }

    @Test
    void 재설정_링크를_Resend_형식으로_요청한다() {
        sender.sendPasswordReset(EMAIL, RESET_URL, Duration.ofMinutes(30));

        ReceivedRequest request = stub.last();
        assertThat(request.to()).isEqualTo(EMAIL);
        assertThat(request.body().get("subject").asString()).isEqualTo("[정부 혜택 도우미] 비밀번호 재설정");
        assertThat(request.text()).contains(RESET_URL).contains("30분");
    }

    @Test
    void 오류_응답이면_상태_코드만_담은_예외를_던진다() {
        // Resend의 검증 오류 본문에는 보낸 값이 들어갈 수 있다. 예외에 본문이 옮겨지면 로그에 주소가 남는다.
        stub.respondWith(422, "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to` field: "
                + EMAIL + "\"}");

        assertThatThrownBy(() -> sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10)))
                .isInstanceOf(AccountMailSendException.class)
                .hasMessage("Resend 응답 상태 422")
                .hasNoCause();

        stub.respondWith(500, "{\"statusCode\":500,\"name\":\"internal_server_error\",\"message\":\"" + EMAIL + "\"}");

        assertThatThrownBy(() -> sender.sendPasswordReset(EMAIL, RESET_URL, Duration.ofMinutes(30)))
                .isInstanceOf(AccountMailSendException.class)
                .hasMessage("Resend 응답 상태 500")
                .hasNoCause();
    }

    @Test
    void 연결이_끊기면_원인_유형만_담은_예외를_던진다() {
        stub.disconnect();

        assertThatThrownBy(() -> sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10)))
                .isInstanceOf(AccountMailSendException.class)
                .hasMessageStartingWith("Resend 연결 실패: ")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(EMAIL, CODE, API_KEY));
    }

    @Test
    void 서버에_연결하지_못하면_원인_유형만_담은_예외를_던진다() {
        ResendStubServer closed = ResendStubServer.start();
        String baseUrl = closed.baseUrl();
        closed.close();
        ResendAccountMailSender unreachable = new ResendAccountMailSender(properties(baseUrl));

        assertThatThrownBy(() -> unreachable.sendPasswordReset(EMAIL, RESET_URL, Duration.ofMinutes(30)))
                .isInstanceOf(AccountMailSendException.class)
                .hasMessageStartingWith("Resend 연결 실패: ")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(EMAIL, RESET_URL, API_KEY));
    }

    @Test
    void 설정의_toString은_API_키를_가린다() {
        assertThat(properties(stub.baseUrl()).toString()).doesNotContain(API_KEY).contains("apiKey=****");
        assertThat(new ResendProperties(URI.create(stub.baseUrl()), "", FROM, Duration.ofSeconds(1),
                Duration.ofSeconds(1)).toString()).contains("apiKey=(없음)");
    }

    @Test
    void 요청_본문의_toString은_주소와_본문을_가린다() {
        String text = new ResendEmailRequest(FROM, List.of(EMAIL), "제목", "인증번호: " + CODE).toString();

        assertThat(text).doesNotContain(EMAIL, CODE);
    }

    private static ResendProperties properties(String baseUrl) {
        return new ResendProperties(URI.create(baseUrl), API_KEY, FROM, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
