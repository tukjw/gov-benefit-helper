package kr.govbenefit.account.mail;

import java.time.Duration;
import kr.govbenefit.account.config.SesProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Amazon SES API v2 {@code SendEmail}로 메일을 보낸다(결정 0045). 실패하면 다시 보내지 않고
 * {@link AccountMailSendException}을 던진다.
 *
 * <p>SDK의 재시도는 끈다. 켜 두면 429·5xx·연결 실패에서 같은 메일을 여러 번 보낸다. 자격 증명과 리전은 설정 값으로만
 * 정하고 SDK 기본 체인(환경 변수, {@code ~/.aws}, 인스턴스 메타데이터)은 쓰지 않는다.
 *
 * <p>SES 오류 메시지는 예외로 옮기지 않는다. 샌드박스에서 인증하지 않은 수신자로 보내면 메시지에 수신 주소가 들어가고,
 * 예외는 그대로 로그에 남기 때문이다.
 */
public class SesAccountMailSender implements AccountMailSender, AutoCloseable {

    private static final String SUBJECT_PREFIX = "[정부 혜택 도우미] ";
    private static final String CHARSET = "UTF-8";

    private final String from;
    private final SesV2Client client;

    public SesAccountMailSender(SesProperties properties) {
        this.from = properties.from();
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.readTimeout()))
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        this.client = builder.build();
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

    @Override
    public void close() {
        client.close();
    }

    private void send(String to, String subject, String text) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(to))
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(content(subject))
                                .body(Body.builder().text(content(text)).build())
                                .build())
                        .build())
                .build();
        try {
            client.sendEmail(request);
        } catch (SdkServiceException e) {
            throw new AccountMailSendException("SES 응답 상태 " + e.statusCode() + errorCode(e));
        } catch (SdkClientException e) {
            // 메시지에 무엇이 담길지 보장할 수 없어 원인은 클래스 이름만 남긴다.
            throw new AccountMailSendException("SES 연결 실패: " + rootCause(e).getClass().getSimpleName());
        }
    }

    private static Content content(String data) {
        return Content.builder().data(data).charset(CHARSET).build();
    }

    private static String errorCode(SdkServiceException e) {
        if (e instanceof AwsServiceException aws) {
            AwsErrorDetails details = aws.awsErrorDetails();
            if (details != null && details.errorCode() != null) {
                return " " + details.errorCode();
            }
        }
        return "";
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
