package kr.govbenefit.common.api;

import java.util.List;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류 응답을 {@link ApiErrorResponse}로 바꾼다(결정 0009). Spring MVC 예외는 상위 클래스가 상태 코드를 정하고,
 * 본문만 이 형식으로 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.value() != 400) {
            // 반환값 검증 실패는 서버 오류다. 항목 오류를 내보내지 않는다.
            return handleExceptionInternal(ex, null, headers, status, request);
        }
        List<InputError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new InputError(parameterName(result.getMethodParameter()),
                                text(result.getArgument()), error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, ApiErrorResponse.invalidInput(errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() : ex.getPropertyName();
        InputError error = new InputError(field, text(ex.getValue()), "형식이 올바르지 않습니다.");
        return handleExceptionInternal(ex, ApiErrorResponse.invalidInput(List.of(error)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        Object errorBody = body instanceof ApiErrorResponse ? body : ApiErrorResponse.of(ErrorCode.from(statusCode));
        return super.handleExceptionInternal(ex, errorBody, headers, statusCode, request);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && StringUtils.hasText(requestParam.name())) {
            return requestParam.name();
        }
        if (requestParam != null && StringUtils.hasText(requestParam.value())) {
            return requestParam.value();
        }
        return parameter.getParameterName();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
