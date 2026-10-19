package kr.govbenefit.common.api;

import java.util.List;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;

/**
 * Bean Validation 제약으로 표현하지 않은 입력 검증의 실패. {@code INVALID_INPUT}으로 응답한다.
 * 여러 항목을 한 번에 검사한 결과는 오류 목록으로 담는다(#49).
 */
public class InvalidInputException extends BusinessException {

    private final List<InputError> errors;

    public InvalidInputException(String field, String value, String reason) {
        this(List.of(new InputError(field, value, reason)));
    }

    public InvalidInputException(List<InputError> errors) {
        super(ErrorCode.INVALID_INPUT, detail(errors));
        this.errors = List.copyOf(errors);
    }

    public InputError error() {
        return errors.getFirst();
    }

    @Override
    public List<InputError> errors() {
        return errors;
    }

    private static String detail(List<InputError> errors) {
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("항목 오류가 하나 이상 있어야 합니다.");
        }
        return String.join(", ", errors.stream().map(error -> error.field() + ": " + error.reason()).toList());
    }
}
