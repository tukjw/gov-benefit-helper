package kr.govbenefit.benefit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.benefit.dto.BenefitDetailResponse;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;
import kr.govbenefit.benefit.dto.BenefitSearchResponse;
import kr.govbenefit.benefit.service.BenefitQueryService;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.region.dto.RegionListResponse;
import kr.govbenefit.region.service.RegionService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 혜택 검색(REQ-SEARCH-01, DEC-SEARCH)과 상세(REQ-DETAIL-01). 저장된 데이터만 읽고 공공 API를 호출하지 않는다.
 */
@Tag(name = "혜택", description = "로그인 없이 쓰는 혜택 검색과 상세. 저장된 데이터만 읽으므로 공공 API 장애 중에도 응답한다.")
@RestController
@RequestMapping("/api/benefits")
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitQueryService benefitQueryService;
    private final RegionService regionService;

    @Operation(summary = "혜택 검색", description = """
            혜택 이름으로 찾고 분야·운영 기관 유형·운영 기관 지역으로 거른다(DEC-SEARCH).

            - 키워드는 공백으로 나눈 단어를 모두 이름에 포함한 혜택을 찾는다. 키워드가 없으면 전체 목록이다.
            - 한 필터 안의 여러 값은 OR, 필터 사이와 키워드는 AND다. 값이 여럿이면 파라미터를 반복한다(`field=a&field=b`).
            - 운영 기관 지역은 소관기관 소재지이며 거주 요건이 아니다(결정 0025). 시도를 고르면 그 시도 기관, 도내 시군구 기관, \
            교육청 혜택이고, 시군구를 고르면 그 시군구 기관 혜택만이다. 지역이 없는 기관(중앙행정기관, 공공기관)의 혜택은 \
            지역 필터를 걸면 나오지 않는다. 코드는 지역 목록 API에서 고르고, 프로필 저장과 같은 기준으로 검증한다.
            - 정렬은 이름순이고 이름이 같으면 서비스 ID 순이다.
            - 마지막 쪽을 넘은 번호는 오류가 아니라 `items`가 빈 배열인 200이다.""")
    @ApiResponse(responseCode = "200", description = "검색 결과")
    @ApiResponse(responseCode = "400", description = "요청 값이 범위를 벗어났거나 지역 코드가 올바르지 않다. `code`는 `INVALID_INPUT`이고 항목별 이유는 `errors`에 있다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.",
                             "errors": [{"field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다."}]}""")))
    @GetMapping
    public ResponseEntity<BenefitSearchResponse> search(@ParameterObject @Valid BenefitSearchRequest request) {
        return ResponseEntity.ok(benefitQueryService.search(request.toCondition(), request.page(), request.size()));
    }

    @Operation(summary = "지역 목록", description = """
            운영 기관 지역 필터(`agencySidoCode`·`agencySigunguCode`)에 쓰는 시도·시군구 코드(결정 0021, 0025). \
            프로필 거주지 선택지의 지역과 같다. 교육청은 따로 없고 시도 필터에 포함된다.""")
    @ApiResponse(responseCode = "200", description = "지역 목록")
    @GetMapping("/regions")
    public ResponseEntity<RegionListResponse> regions() {
        return ResponseEntity.ok(new RegionListResponse(regionService.sidos()));
    }

    /**
     * 없는 서비스 ID는 404다. 상세를 아직 받지 못한 혜택은 {@code detail}이 null인 200이다.
     */
    @Operation(summary = "혜택 상세", description = """
            서비스 ID로 저장된 혜택의 상세와 신청처를 조회한다. \
            상세를 아직 받지 못한 혜택도 존재하는 혜택이므로 `detail`이 `null`인 200이다.""")
    @ApiResponse(responseCode = "200", description = "혜택 상세")
    @ApiResponse(responseCode = "404", description = "없는 서비스 ID다. `code`는 `NOT_FOUND`이고 `errors`는 빈 배열이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "NOT_FOUND", "message": "요청한 경로나 자원을 찾을 수 없습니다.", "errors": []}""")))
    @GetMapping("/{serviceId}")
    public ResponseEntity<BenefitDetailResponse> detail(
            @Parameter(description = "서비스 ID. gov.kr 상세 주소의 마지막 부분과 같다.",
                    schema = @Schema(type = "string", example = "654000000031"))
            @PathVariable String serviceId) {
        return ResponseEntity.ok(benefitQueryService.getDetail(serviceId));
    }
}
