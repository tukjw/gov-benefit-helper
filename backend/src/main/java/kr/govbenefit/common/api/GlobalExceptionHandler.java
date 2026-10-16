package kr.govbenefit.common.api;

import java.util.List;
import java.util.Set;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류 응답을 {@link ApiErrorResponse}로 바꾼다(결정 0009). Spring MVC 예외는 상위 클래스가 상태 코드를 정하고,
 * 본문만 이 형식으로 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_MISMATCH = "형식이 올바르지 않습니다.";
    /** 입력 오류에 받은 값을 넣지 않는 항목. 비밀번호 원문과 일회용 토큰이 응답에 나가지 않게 한다. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "newPassword", "token", "verificationToken");

    /**
     * 상태 코드는 오류 코드가 정한다. 토큰은 유효하지만 쓸 수 없는 경우(토큰 주인 없음)도 보안 필터의 401과 같이
     * WWW-Authenticate를 붙인다(RFC 6750). 요청 횟수 제한은 Retry-After를 붙인다(RFC 9110).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.code() == ErrorCode.UNAUTHENTICATED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        if (ex instanceof RateLimitedException rateLimited) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimited.retryAfterSeconds()));
        }
        return response.body(ApiErrorResponse.of(ex.code(), ex.errors()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        // 내부 메시지는 응답에 넣지 않는다.
        log.error("요청 처리 중 예상하지 못한 오류", ex);
        return ResponseEntity.internalServerError().body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 요청 객체({@code @Valid} DTO)의 바인딩·검증 실패. 형식 변환 실패는 Spring 기본 문구(영어) 대신 고정 문구로 바꾼다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InputError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new InputError(error.getField(),
                        SENSITIVE_FIELDS.contains(error.getField()) ? null : text(error.getRejectedValue()),
                        error.isBindingFailure() ? TYPE_MISMATCH : error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, ApiErrorResponse.invalidInput(errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ApiErrorResponse errorBody = body instanceof ApiErrorResponse response
                ? response
                : ApiErrorResponse.of(ErrorCode.from(statusCode));
        return super.handleExceptionInternal(ex, errorBody, headers, statusCode, request);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
