package kr.govbenefit.benefit.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.govbenefit.benefit.dto.BenefitSearchCondition;
import kr.govbenefit.benefit.dto.BenefitSearchResponse;
import kr.govbenefit.benefit.service.BenefitQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 혜택 검색(REQ-SEARCH-01, DEC-SEARCH). 저장된 데이터만 읽고 공공 API를 호출하지 않는다.
 */
@RestController
@RequestMapping("/api/benefits")
@RequiredArgsConstructor
public class BenefitController {

    private static final String KEYWORD_LENGTH = "100자까지 입력할 수 있습니다.";
    private static final String PAGE_RANGE = "1 이상이어야 합니다.";
    private static final String SIZE_RANGE = "1 이상 100 이하여야 합니다.";

    private final BenefitQueryService benefitQueryService;

    /**
     * 필터 값이 여럿이면 파라미터를 반복한다({@code field=a&field=b}).
     */
    @GetMapping
    public BenefitSearchResponse search(
            @RequestParam(required = false)
            @Size(max = BenefitSearchCondition.MAX_KEYWORD_LENGTH, message = KEYWORD_LENGTH) String keyword,
            @RequestParam(name = "field", required = false) List<String> fields,
            @RequestParam(name = "agencyType", required = false) List<String> agencyTypes,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = PAGE_RANGE) int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = SIZE_RANGE) @Max(value = 100, message = SIZE_RANGE) int size) {
        return benefitQueryService.search(BenefitSearchCondition.of(keyword, fields, agencyTypes), page, size);
    }
}
