package kr.govbenefit.common.api;

import java.util.List;
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

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidInput(InvalidInputException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.invalidInput(List.of(ex.error())));
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
                .map(error -> new InputError(error.getField(), text(error.getRejectedValue()),
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
