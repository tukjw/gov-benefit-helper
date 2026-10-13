package kr.govbenefit.common.api;

import java.util.List;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;

/**
 * Bean Validation 제약으로 표현하지 않은 입력 검증의 실패. {@code INVALID_INPUT}으로 응답한다.
 */
public class InvalidInputException extends BusinessException {

    private final InputError error;

    public InvalidInputException(String field, String value, String reason) {
        super(ErrorCode.INVALID_INPUT, field + ": " + reason);
        this.error = new InputError(field, value, reason);
    }

    public InputError error() {
        return error;
    }

    @Override
    public List<InputError> errors() {
        return List.of(error);
    }
}
