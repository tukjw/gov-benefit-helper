package kr.govbenefit.benefit.controller;

import jakarta.validation.Valid;
import kr.govbenefit.benefit.dto.BenefitDetailResponse;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;
import kr.govbenefit.benefit.dto.BenefitSearchResponse;
import kr.govbenefit.benefit.service.BenefitQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 혜택 검색(REQ-SEARCH-01, DEC-SEARCH)과 상세(REQ-DETAIL-01, DEC-FRESHNESS). 저장된 데이터만 읽고 공공 API를 호출하지 않는다.
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

    /**
     * 없는 서비스 ID는 404다. 상세를 아직 받지 못한 혜택은 {@code detail}이 null인 200이다.
     */
    @GetMapping("/{serviceId}")
    public BenefitDetailResponse detail(@PathVariable String serviceId) {
        return benefitQueryService.getDetail(serviceId);
    }
}
