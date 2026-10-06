package kr.govbenefit.common.api;

import org.springframework.http.HttpStatusCode;

/**
 * 오류 응답의 {@code code}. 클라이언트가 분기에 쓰는 고정 값이다(결정 0009).
 */
public enum ErrorCode {
    INVALID_INPUT("요청 값이 올바르지 않습니다."),
    NOT_FOUND("요청한 경로나 자원을 찾을 수 없습니다."),
    INTERNAL_ERROR("서버에서 요청을 처리하지 못했습니다."),
    /** 위 코드에 해당하지 않는 실패. 상태 코드로 원인을 구분한다. */
    REQUEST_FAILED("요청을 처리할 수 없습니다.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public static ErrorCode from(HttpStatusCode status) {
        if (status.value() == 400) {
            return INVALID_INPUT;
        }
        if (status.value() == 404) {
            return NOT_FOUND;
        }
        if (status.is5xxServerError()) {
            return INTERNAL_ERROR;
        }
        return REQUEST_FAILED;
    }
}
