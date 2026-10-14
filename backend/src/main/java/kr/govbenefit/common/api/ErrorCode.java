package kr.govbenefit.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 오류 응답의 {@code code}와 응답 상태 코드. 클라이언트가 분기에 쓰는 고정 값이다(결정 0009).
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로나 자원을 찾을 수 없습니다."),
    /** 토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인이 없다. 재발급의 리프레시 토큰도 같다. 원인은 나누지 않는다. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    /** 로그인 실패. 비밀번호가 틀린 경우와 가입되지 않은 이메일을 구분하지 않는다. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    /** 쿠키로 인증하는 요청이 CSRF 방어를 통과하지 못했다(결정 0017). 클라이언트 구현 오류라 원인을 숨기지 않는다. */
    CSRF_REJECTED(HttpStatus.FORBIDDEN, "허용되지 않은 출처의 요청입니다."),
    /** 5xx 전체. 상태 코드가 하나로 정해지지 않는다. */
    INTERNAL_ERROR(null, "서버에서 요청을 처리하지 못했습니다."),
    /** 위 코드에 해당하지 않는 실패. 상태 코드로 원인을 구분한다. */
    REQUEST_FAILED(null, "요청을 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * 이 코드로 나가는 응답의 상태 코드. {@code null}이면 {@link BusinessException}으로 던질 수 없고 Spring MVC 예외와
     * 예상하지 못한 예외에서만 나간다.
     */
    public HttpStatus status() {
        return status;
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
