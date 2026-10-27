package kr.govbenefit.account.mail;

/**
 * 메일을 보내지 못했다. 메시지에는 상태 코드와 실패 유형만 담고, 수신 주소·본문·응답 본문·API 키는 담지 않는다(결정 0032).
 * {@link AccountMailListener}가 예외를 그대로 로그에 남기기 때문이다.
 */
public class AccountMailSendException extends RuntimeException {

    public AccountMailSendException(String message) {
        super(message);
    }
}
