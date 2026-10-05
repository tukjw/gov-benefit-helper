package kr.govbenefit.benefit.controller;

import jakarta.validation.Valid;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;
import kr.govbenefit.benefit.dto.BenefitSearchResponse;
import kr.govbenefit.benefit.service.BenefitQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 혜택 검색(REQ-SEARCH-01, DEC-SEARCH). 저장된 데이터만 읽고 공공 API를 호출하지 않는다.
 */
@RestController
@RequestMapping("/api/benefits")
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitQueryService benefitQueryService;

    @GetMapping
    public BenefitSearchResponse search(@Valid BenefitSearchRequest request) {
        return benefitQueryService.search(request.toCondition(), request.page(), request.size());
    }
}
