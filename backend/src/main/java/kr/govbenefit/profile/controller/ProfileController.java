package kr.govbenefit.profile.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import kr.govbenefit.profile.dto.ProfileOptionsResponse;
import kr.govbenefit.profile.dto.ProfileRequest;
import kr.govbenefit.profile.dto.ProfileResponse;
import kr.govbenefit.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 최소 프로필(REQ-PROFILE-01, DEC-PROFILE). 사용자는 토큰으로만 정한다(결정 0018).
 */
@Tag(name = "프로필", description = "조건 비교와 운영 기관 지역 필터에 쓰는 최소 프로필. 모든 항목은 선택 입력이며 소득은 받지 않는다.")
@RestController
@RequestMapping("/api/me/profile")
@RequiredArgsConstructor
public class ProfileController {

    private static final String UNAUTHENTICATED = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""";
    private static final String UNAUTHENTICATED_EXAMPLE = """
            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""";

    private final ProfileService profileService;

    @Operation(summary = "내 프로필", description = "저장한 적이 없으면 모든 값이 `null`이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "토큰 주인의 프로필")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @GetMapping
    public ResponseEntity<ProfileResponse> get(@CurrentUserId Long userId) {
        return ResponseEntity.ok(profileService.get(userId));
    }

    @Operation(summary = "내 프로필 저장", description = """
            본문 전체로 프로필을 바꾼다. 처음이면 만든다. 빠진 필드와 `null`은 미입력이다.

            - 속성은 `null`이면 미입력, `["NONE"]`이면 해당 없음이다. 빈 배열은 거절한다.
            - 지역 코드와 속성 값은 선택지 API(`GET /api/me/profile/options`)에서 고른다.
            - 잘못된 항목은 모두 `errors`에 담아 한 번에 거절하며, 거절하면 아무것도 바꾸지 않는다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "저장한 프로필")
    @ApiResponse(responseCode = "400", description = """
            지원하지 않는 값이나 모순된 입력이다. `code`는 `INVALID_INPUT`이고 항목 오류는 `errors`에 있다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.", "errors": [
                              {"field": "sigunguCode", "value": "5805000", "reason": "선택한 시도의 시군구가 아닙니다."},
                              {"field": "lifeAttributes", "value": "NONE,EMPLOYEE", "reason": "해당 없음(NONE)은 다른 값과 함께 고를 수 없습니다."}
                            ]}""")))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @PutMapping
    public ResponseEntity<ProfileResponse> put(@CurrentUserId Long userId, @RequestBody ProfileRequest request) {
        return ResponseEntity.ok(profileService.put(userId, request));
    }

    @Operation(summary = "프로필 선택지", description = "시도별 시군구 목록과 생애·가구 속성의 값·이름이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "선택지")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @GetMapping("/options")
    public ResponseEntity<ProfileOptionsResponse> options() {
        return ResponseEntity.ok(profileService.options());
    }
}
