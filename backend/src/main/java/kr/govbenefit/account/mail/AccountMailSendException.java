package kr.govbenefit.account.mail;

/**
 * 메일을 보내지 못했다. 메시지에는 상태 코드와 실패 유형만 담고, 수신 주소·본문·응답 본문·접근 키는 담지 않는다(결정 0047).
 * {@link AccountMailListener}가 예외를 그대로 로그에 남기기 때문이다.
 *
 * <p>{@link #isRetryable()}는 같은 메일을 나중에 다시 보내면 성공할 수 있는지다. 429·5xx 응답과 연결 실패(연결 거부·끊김·시간
 * 초과)는 {@code true}, 그 밖의 SES 오류(400·403·404 등)는 {@code false}다. 계정 메일은 이 값과 관계없이 다시 보내지 않는다.
 */
public class AccountMailSendException extends RuntimeException {

    private final boolean retryable;

    public AccountMailSendException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
