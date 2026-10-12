package kr.govbenefit.common.api;

import org.springframework.http.HttpStatusCode;

/**
 * 오류 응답의 {@code code}. 클라이언트가 분기에 쓰는 고정 값이다(결정 0009).
 */
public enum ErrorCode {
    INVALID_INPUT("요청 값이 올바르지 않습니다."),
    NOT_FOUND("요청한 경로나 자원을 찾을 수 없습니다."),
    /** 토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인이 없다. 원인은 나누지 않는다. */
    UNAUTHENTICATED("인증이 필요합니다."),
    /** 로그인 실패. 비밀번호가 틀린 경우와 가입되지 않은 이메일을 구분하지 않는다. */
    INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_EMAIL("이미 가입된 이메일입니다."),
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
        if (status.value() == 401) {
            return UNAUTHENTICATED;
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
