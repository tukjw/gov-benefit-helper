package kr.govbenefit.collection.client;

/**
 * 공공서비스 API 호출 실패. 메시지에는 상태 코드와 응답의 code·msg만 담고 요청 헤더(인증키)는 담지 않는다.
 */
public class Gov24ApiException extends RuntimeException {

    public Gov24ApiException(String message) {
        super(message);
    }

    public Gov24ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
