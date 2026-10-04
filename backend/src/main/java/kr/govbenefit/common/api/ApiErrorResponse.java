package kr.govbenefit.common.api;

import java.util.List;

/**
 * 실패한 요청의 응답 본문(결정 0009). 성공 응답은 감싸지 않는다.
 *
 * @param code    {@link ErrorCode} 이름
 * @param message 사람이 읽는 문구
 * @param errors  입력 항목별 오류. 없으면 빈 배열이다.
 */
public record ApiErrorResponse(String code, String message, List<InputError> errors) {

    public static ApiErrorResponse of(ErrorCode code) {
        return new ApiErrorResponse(code.name(), code.message(), List.of());
    }

    public static ApiErrorResponse invalidInput(List<InputError> errors) {
        return new ApiErrorResponse(ErrorCode.INVALID_INPUT.name(), ErrorCode.INVALID_INPUT.message(), errors);
    }

    /**
     * @param field  요청 파라미터 이름
     * @param value  받은 값. 값이 없었으면 null이다.
     * @param reason 거절한 이유
     */
    public record InputError(String field, String value, String reason) {
    }
}
