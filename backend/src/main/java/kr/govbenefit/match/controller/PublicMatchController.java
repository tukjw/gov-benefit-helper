package kr.govbenefit.match.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.match.dto.MatchListRequest;
import kr.govbenefit.match.dto.MatchListResponse;
import kr.govbenefit.match.dto.MatchResponse;
import kr.govbenefit.match.service.MatchService;
import kr.govbenefit.profile.dto.ProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청에 담은 프로필 값과 혜택의 지원조건 비교(REQ-MATCH-01). 로그인 없이 쓰며 값을 저장하지 않는다(DEC-AUTH 공개 범위).
 * 판정·범위·정렬·페이지는 {@link MatchController}와 같다.
 */
@Tag(name = "조건 비교", description = "내 프로필과 혜택의 지원조건 코드를 비교한 결과. 신청 가능 확정이 아니다.")
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class PublicMatchController {

    private static final String NOT_STORED = """
            본문은 프로필 저장(`PUT /api/me/profile`)과 같은 형식이며 저장하지 않는다. 본문이 없거나 `{}`이면 모든 항목이 미입력이다. \
            토큰을 보내도 저장된 프로필을 읽지 않고 본문 값으로만 비교한다.""";
    private static final String INVALID_PROFILE = """
            지원하지 않는 값이나 모순된 입력이다. 프로필 저장과 같은 항목별 오류이며 `code`는 `INVALID_INPUT`이다.""";
    private static final String INVALID_PROFILE_EXAMPLE = """
            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.", "errors": [
              {"field": "sigunguCode", "value": "5805000", "reason": "선택한 시도의 시군구가 아닙니다."}
            ]}""";

    private final MatchService matchService;

    @Operation(summary = "프로필 값으로 조건 비교 결과 순 혜택 목록", description = NOT_STORED + """


            판정·순서·지역 범위(`regionScope`)·쪽 처리는 로그인 사용자의 목록(`GET /api/me/matches`)과 같다. \
            같은 프로필 값이면 결과도 같다.

            - 개인 대상이 아닌 혜택(`MISMATCH`)은 목록에 없다.
            - 지역 범위의 기본은 본문에 거주지가 있으면 `MY_REGION`, 없으면 `ALL`이다.
            - 프로필 값을 보내지 않으면 개인 대상 혜택이 모두 `NEEDS_CHECK`다.
            - 쪽 번호·크기가 범위를 벗어나면 본문을 검사하기 전에 거절한다.""")
    @ApiResponse(responseCode = "200", description = "조건 비교 결과 순 혜택 목록")
    @ApiResponse(responseCode = "400", description = INVALID_PROFILE + " 쪽 번호·크기·지역 범위 오류도 같은 형식이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = INVALID_PROFILE_EXAMPLE)))
    @PostMapping
    public ResponseEntity<MatchListResponse> list(@ParameterObject @Valid MatchListRequest request,
                                                  @RequestBody(required = false) ProfileRequest profile) {
        return ResponseEntity.ok(matchService.listWith(orEmpty(profile), request.regionScope(), request.page(),
                request.size()));
    }

    @Operation(summary = "프로필 값으로 혜택 하나의 조건 비교", description = NOT_STORED + """


            판정은 로그인 사용자의 조건 비교(`GET /api/me/matches/{serviceId}`)와 같다. 본문을 먼저 검사하므로 \
            잘못된 값이면 서비스 ID와 관계없이 400이다.""")
    @ApiResponse(responseCode = "200", description = "비교 결과")
    @ApiResponse(responseCode = "400", description = INVALID_PROFILE,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = INVALID_PROFILE_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "수집되지 않은 서비스 ID다. `code`는 `NOT_FOUND`다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "NOT_FOUND", "message": "요청한 경로나 자원을 찾을 수 없습니다.", "errors": []}""")))
    @PostMapping("/{serviceId}")
    public ResponseEntity<MatchResponse> match(@Parameter(description = "서비스 ID. gov.kr 상세 주소의 마지막 부분과 같다.",
                                                       schema = @Schema(type = "string", example = "654000000031"))
                                               @PathVariable String serviceId,
                                               @RequestBody(required = false) ProfileRequest profile) {
        return ResponseEntity.ok(matchService.matchWith(orEmpty(profile), serviceId));
    }

    private static ProfileRequest orEmpty(ProfileRequest profile) {
        return profile == null ? ProfileRequest.empty() : profile;
    }
}
