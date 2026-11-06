package kr.govbenefit.account.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import kr.govbenefit.account.mail.SesStubServer.ReceivedRequest;
import kr.govbenefit.account.config.SesProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SES 요청 형식, 실패 예외와 재시도 가능 여부, SDK 재시도 없음(결정 0045). 실제 SES 대신 로컬 stub 서버로 보낸다.
 */
class SesAccountMailSenderTest {

    private static final String ACCESS_KEY_ID = "AKIATESTSENDER7Q2X";
    private static final String SECRET_ACCESS_KEY = "test/Secret+Key9f8e7d6c5b4a3";
    private static final String REGION = "ap-northeast-2";
    private static final String FROM = "정부 혜택 도우미 <no-reply@govbenefit.kr>";
    private static final String EMAIL = "alice@example.com";
    private static final String CODE = "042917";
    private static final String RESET_URL = "https://govbenefit.kr/reset-password#token=Qm9yZWRUb2tlblZhbHVl";

    private static final SesStubServer stub = SesStubServer.start();

    private final SesAccountMailSender sender = new SesAccountMailSender(properties(stub.endpoint()));

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @AfterEach
    void closeSender() {
        sender.close();
    }

    @Test
    void 가입_인증번호를_SES_형식으로_요청한다() {
        sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10));

        ReceivedRequest request = stub.last();
        assertThat(stub.received()).hasSize(1);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(SesStubServer.SEND_EMAIL_PATH);
        assertThat(request.authorization())
                .startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY_ID + "/")
                .contains("/" + REGION + "/ses/aws4_request")
                .doesNotContain(SECRET_ACCESS_KEY);
        assertThat(request.from()).isEqualTo(FROM);
        assertThat(request.toAddresses()).containsExactly(EMAIL);
        assertThat(request.subject()).isEqualTo("[정부 혜택 도우미] 가입 인증번호");
        assertThat(request.simple().get("Subject").get("Charset").asString()).isEqualTo("UTF-8");
        assertThat(request.text()).contains("인증번호: " + CODE).contains("10분");
        assertThat(request.simple().get("Body").has("Html")).isFalse();
    }

    @Test
    void 재설정_링크를_SES_형식으로_요청한다() {
        sender.sendPasswordReset(EMAIL, RESET_URL, Duration.ofMinutes(30));

        ReceivedRequest request = stub.last();
        assertThat(request.toAddresses()).containsExactly(EMAIL);
        assertThat(request.subject()).isEqualTo("[정부 혜택 도우미] 비밀번호 재설정");
        assertThat(request.text()).contains(RESET_URL).contains("30분");
    }

    @ParameterizedTest
    @CsvSource({
            // 샌드박스에서 인증하지 않은 수신자로 보내면 설명에 수신 주소가 들어간다.
            "400, MessageRejected, Email address is not verified. The following identities failed the check in region"
                    + " AP-NORTHEAST-2: " + EMAIL + ", false",
            "403, AccessDeniedException, User is not authorized to perform ses:SendEmail, false",
            "404, NotFoundException, Resource not found, false",
            "429, TooManyRequestsException, Maximum sending rate exceeded. " + EMAIL + ", true",
            "500, InternalFailure, Internal failure for " + EMAIL + ", true"})
    void 오류_응답이면_상태와_오류_코드만_담은_예외를_던지고_다시_보내지_않는다(int status, String errorType,
                                                                  String message, boolean retryable) {
        stub.respondWithError(status, errorType, message);

        assertThatThrownBy(() -> sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10)))
                .isInstanceOfSatisfying(AccountMailSendException.class,
                        e -> assertThat(e.isRetryable()).isEqualTo(retryable))
                .hasMessage("SES 응답 상태 " + status + " " + errorType)
                .hasNoCause();
        assertThat(stub.received()).hasSize(1);
    }

    @Test
    void 연결이_끊기면_원인_유형만_담은_예외를_던지고_다시_보내지_않는다() {
        stub.disconnect();

        assertThatThrownBy(() -> sender.sendSignupCode(EMAIL, CODE, Duration.ofMinutes(10)))
                .isInstanceOfSatisfying(AccountMailSendException.class, e -> assertThat(e.isRetryable()).isTrue())
                .hasMessageStartingWith("SES 연결 실패: ")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(EMAIL, CODE, ACCESS_KEY_ID,
                        SECRET_ACCESS_KEY));
        assertThat(stub.received()).hasSize(1);
    }

    @Test
    void 서버에_연결하지_못하면_원인_유형만_담은_예외를_던진다() {
        SesStubServer closed = SesStubServer.start();
        URI endpoint = closed.endpoint();
        closed.close();

        try (SesAccountMailSender unreachable = new SesAccountMailSender(properties(endpoint))) {
            assertThatThrownBy(() -> unreachable.sendPasswordReset(EMAIL, RESET_URL, Duration.ofMinutes(30)))
                    .isInstanceOfSatisfying(AccountMailSendException.class, e -> assertThat(e.isRetryable()).isTrue())
                    .hasMessageStartingWith("SES 연결 실패: ")
                    .hasNoCause()
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(EMAIL, RESET_URL, ACCESS_KEY_ID,
                            SECRET_ACCESS_KEY));
        }
    }

    @Test
    void 설정의_toString은_접근_키를_가린다() {
        assertThat(properties(stub.endpoint()).toString())
                .doesNotContain(ACCESS_KEY_ID, SECRET_ACCESS_KEY)
                .contains("accessKeyId=****", "secretAccessKey=****");
        assertThat(new SesProperties("", null, REGION, FROM, null, Duration.ofSeconds(1), Duration.ofSeconds(1))
                .toString()).contains("accessKeyId=(없음)", "secretAccessKey=(없음)");
    }

    private static SesProperties properties(URI endpoint) {
        return new SesProperties(ACCESS_KEY_ID, SECRET_ACCESS_KEY, REGION, FROM, endpoint, Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}
