package kr.govbenefit.common.api;

import java.util.List;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import org.springframework.http.HttpStatus;

/**
 * 응답 오류 코드가 정해진 실패. 서비스와 요청 검증에서 던진다. 오류 코드만 정하고 상태 코드는 {@link ErrorCode}가 정한다(결정 0009).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        this(code, null);
    }

    /**
     * @param detail 예외 메시지에만 남는 설명. 응답에는 나가지 않는다.
     */
    public BusinessException(ErrorCode code, String detail) {
        super(detail == null ? code.name() : code.name() + ": " + detail);
        if (code.status() == null) {
            throw new IllegalArgumentException("상태 코드가 정해지지 않은 오류 코드는 던질 수 없습니다: " + code);
        }
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return code.status();
    }

    /**
     * 응답의 입력 항목별 오류. 기본은 빈 목록이다.
     */
    public List<InputError> errors() {
        return List.of();
    }
}
