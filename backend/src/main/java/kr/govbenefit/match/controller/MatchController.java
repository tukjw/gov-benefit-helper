package kr.govbenefit.match.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import kr.govbenefit.match.dto.MatchResponse;
import kr.govbenefit.match.service.MatchService;
import lombok.RequiredArgsConstructor;
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

    private final MatchService matchService;

    @Operation(summary = "혜택 하나의 조건 비교", description = """
            개인 대상 여부·연령·성별·생애·가구 속성을 비교한다(DEC-MATCH). 소득 등 그 밖의 조건은 비교하지 않는다.

            - 개인 대상이 아닌 혜택만 `MISMATCH`이고, 개인 대상 혜택은 코드상 벗어나도 `NEEDS_CHECK`다.
            - 프로필 미입력·코드 없음·지원조건 미수집인 항목은 `NEEDS_CHECK`다.
            - 프로필은 요청할 때의 값으로 비교하므로, 수정하면 다음 요청에 바로 반영된다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "비교 결과")
    @ApiResponse(responseCode = "401", description = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""")))
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
