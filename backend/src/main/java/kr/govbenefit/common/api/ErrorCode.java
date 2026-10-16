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
    /** 인증번호가 틀렸다. 같은 번호로 다시 입력할 수 있다(결정 0019). */
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않습니다."),
    /** 인증번호가 만료됐거나, 이미 썼거나, 다시 받아 무효가 됐거나, 요청한 적이 없다. 번호를 다시 받아야 한다(결정 0019). */
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 인증번호를 다시 받아 주세요."),
    /** 인증번호를 비교한 횟수가 제한에 이르렀다. 맞는 번호도 거절하므로 번호를 다시 받아야 한다(결정 0019). */
    VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST, "인증번호 입력 횟수를 넘었습니다. 인증번호를 다시 받아 주세요."),
    /** 가입 티켓이 없거나, 만료·사용됐거나, 다른 이메일로 받은 것이다. 원인은 나누지 않는다(결정 0019). */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증을 마쳐야 가입할 수 있습니다."),
    /** 재설정 토큰이 없거나, 형식이 틀렸거나, 만료·사용됐거나, 다시 요청해 무효가 됐다. 원인은 나누지 않는다(결정 0019). */
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "비밀번호 재설정 링크가 유효하지 않습니다. 재설정을 다시 요청해 주세요."),
    /** 요청 횟수 제한. 다시 시도할 수 있는 시각까지의 초를 Retry-After 헤더로 준다({@link RateLimitedException}). */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요."),
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
