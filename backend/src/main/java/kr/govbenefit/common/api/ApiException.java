package kr.govbenefit.common.api;

import org.springframework.http.HttpStatus;

/**
 * 상태 코드와 오류 코드가 정해진 실패. 본문은 {@code ApiErrorResponse.of(code)}다(결정 0009).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code) {
        super(code.name());
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
