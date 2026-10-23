package kr.govbenefit.match.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import kr.govbenefit.match.dto.MatchListRequest;
import kr.govbenefit.match.dto.MatchListResponse;
import kr.govbenefit.match.dto.MatchResponse;
import kr.govbenefit.match.service.MatchService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 프로필과 혜택의 지원조건 비교(REQ-MATCH-01). 사용자는 토큰으로만 정한다(결정 0018).
 */
@Tag(name = "조건 비교", description = "내 프로필과 혜택의 지원조건 코드를 비교한 결과. 신청 가능 확정이 아니다.")
@RestController
@RequestMapping("/api/me/matches")
@RequiredArgsConstructor
public class MatchController {

    private static final String UNAUTHENTICATED = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""";
    private static final String UNAUTHENTICATED_EXAMPLE = """
            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""";

    private final MatchService matchService;

    @Operation(summary = "조건 비교 결과 순 혜택 목록", description = """
            내 프로필로 비교한 결과가 `MATCH`인 혜택, 그다음 `NEEDS_CHECK`인 혜택 순으로 돌려준다(DEC-MATCH). \
            같은 결과 안에서는 이름순이고 이름이 같으면 서비스 ID 순이며, 혜택 검색의 이름순과 같다.

            - 개인 대상이 아닌 혜택(`MISMATCH`)은 목록에 없다. 혜택 검색으로는 찾을 수 있다.
            - `NEEDS_CHECK`인 혜택도 숨기지 않는다. 프로필을 입력하지 않았으면 개인 대상 혜택이 모두 `NEEDS_CHECK`다.
            - 항목의 판정은 같은 혜택의 조건 비교(`GET /api/me/matches/{serviceId}`)와 같다. 비교한 값과 원문은 그 API로 본다.
            - 프로필은 요청할 때의 값으로 비교하므로, 수정하면 다음 요청에 바로 반영된다.
            - 마지막 쪽을 넘은 번호는 오류가 아니라 `items`가 빈 배열인 200이다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "조건 비교 결과 순 혜택 목록")
    @ApiResponse(responseCode = "400", description = "요청 값이 범위를 벗어났다. `code`는 `INVALID_INPUT`이고 항목별 이유는 `errors`에 있다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.",
                             "errors": [{"field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다."}]}""")))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @GetMapping
    public ResponseEntity<MatchListResponse> list(@CurrentUserId Long userId,
                                                  @ParameterObject @Valid MatchListRequest request) {
        return ResponseEntity.ok(matchService.list(userId, request.page(), request.size()));
    }

    @Operation(summary = "혜택 하나의 조건 비교", description = """
            개인 대상 여부·연령·성별·생애·가구 속성을 비교한다(DEC-MATCH). 소득 등 그 밖의 조건은 비교하지 않는다.

            - 개인 대상이 아닌 혜택만 `MISMATCH`이고, 개인 대상 혜택은 코드상 벗어나도 `NEEDS_CHECK`다.
            - 프로필 미입력·코드 없음·지원조건 미수집인 항목은 `NEEDS_CHECK`다.
            - 프로필은 요청할 때의 값으로 비교하므로, 수정하면 다음 요청에 바로 반영된다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "비교 결과")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "수집되지 않은 서비스 ID다. `code`는 `NOT_FOUND`다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "NOT_FOUND", "message": "요청한 경로나 자원을 찾을 수 없습니다.", "errors": []}""")))
    @GetMapping("/{serviceId}")
    public ResponseEntity<MatchResponse> match(@CurrentUserId Long userId,
                                               @Parameter(description = "서비스 ID. gov.kr 상세 주소의 마지막 부분과 같다.",
                                                       schema = @Schema(type = "string", example = "654000000031"))
                                               @PathVariable String serviceId) {
        return ResponseEntity.ok(matchService.match(userId, serviceId));
    }
}
