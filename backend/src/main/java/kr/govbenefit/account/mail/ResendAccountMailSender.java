package kr.govbenefit.account.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import kr.govbenefit.account.config.ResendProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Resend HTTP API로 메일을 보낸다(결정 0032). 실패하면 재시도하지 않고 {@link AccountMailSendException}을 던진다.
 *
 * <p>오류 응답의 본문은 읽지 않는다. 검증 오류 본문에 수신 주소가 들어갈 수 있고, 예외는 그대로 로그에 남기 때문이다.
 */
public class ResendAccountMailSender implements AccountMailSender {

    private static final String USER_AGENT = "gov-benefit-helper";
    private static final String SUBJECT_PREFIX = "[정부 혜택 도우미] ";

    private final String from;
    private final RestClient restClient;

    public ResendAccountMailSender(ResendProperties properties) {
        this.from = properties.from();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                // 키는 기본 헤더로만 두고 URL·본문에 넣지 않는다. Resend는 User-Agent가 없으면 403이다.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    @Override
    public void sendSignupCode(String email, String code, Duration validFor) {
        send(email, SUBJECT_PREFIX + "가입 인증번호", """
                정부 혜택 도우미 가입 인증번호입니다.

                인증번호: %s

                %d분 동안 쓸 수 있습니다. 가입을 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(code, validFor.toMinutes()));
    }

    @Override
    public void sendPasswordReset(String email, String resetUrl, Duration validFor) {
        send(email, SUBJECT_PREFIX + "비밀번호 재설정", """
                아래 링크에서 정부 혜택 도우미 비밀번호를 새로 정하세요.

                %s

                링크는 %d분 동안 한 번만 쓸 수 있습니다. 재설정을 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(resetUrl, validFor.toMinutes()));
    }

    private void send(String to, String subject, String text) {
        int status;
        try {
            status = restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ResendEmailRequest(from, List.of(to), subject, text))
                    .exchange((request, response) -> response.getStatusCode().value(), true);
        } catch (ResourceAccessException e) {
            // JDK 예외 메시지에 무엇이 담길지 보장할 수 없어 원인은 클래스 이름만 남긴다.
            throw new AccountMailSendException(
                    "Resend 연결 실패: " + e.getMostSpecificCause().getClass().getSimpleName());
        }
        if (status < 200 || status >= 300) {
            throw new AccountMailSendException("Resend 응답 상태 " + status);
        }
    }
}
