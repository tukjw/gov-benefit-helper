package kr.govbenefit.common.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 실패한 요청의 응답 본문(결정 0009). 성공 응답은 감싸지 않는다.
 *
 * @param code    {@link ErrorCode} 이름
 * @param message 사람이 읽는 문구
 * @param errors  입력 항목별 오류. 없으면 빈 배열이다.
 */
@Schema(description = "실패한 요청의 응답 본문(결정 0009). 성공 응답은 감싸지 않는다.",
        requiredProperties = {"code", "message", "errors"})
public record ApiErrorResponse(
        @Schema(description = """
                클라이언트가 분기에 쓰는 고정 값. `INVALID_INPUT`(400, 항목 오류는 `errors`), `NOT_FOUND`(404), \
                `INTERNAL_ERROR`(5xx), `REQUEST_FAILED`(그 밖의 실패, 상태 코드로 원인을 구분).""",
                allowableValues = {"INVALID_INPUT", "NOT_FOUND", "INTERNAL_ERROR", "REQUEST_FAILED"},
                example = "INVALID_INPUT")
        String code,
        @Schema(description = "사람이 읽는 문구. 분기에 쓰지 않는다.", example = "요청 값이 올바르지 않습니다.")
        String message,
        @ArraySchema(arraySchema = @Schema(description = "입력 항목별 오류. 항상 배열이며 항목 오류가 없으면 빈 배열이다."))
        List<InputError> errors) {

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
    @Schema(description = "입력 항목 하나의 오류")
    public record InputError(
            @Schema(description = "요청 파라미터 이름", example = "size") String field,
            @Schema(description = "받은 값. 값이 없었으면 `null`이다.", example = "200") String value,
            @Schema(description = "거절한 이유", example = "1 이상 100 이하여야 합니다.") String reason) {
    }
}
