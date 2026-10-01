package kr.govbenefit.benefit.service;

import java.util.Optional;
import kr.govbenefit.benefit.domain.DetailStatus;
import kr.govbenefit.benefit.dto.BenefitView;
import kr.govbenefit.benefit.repository.BenefitDetailRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenefitQueryService {

    private final BenefitRepository benefitRepository;
    private final BenefitDetailRepository benefitDetailRepository;

    public Optional<BenefitView> findByServiceId(String serviceId) {
        return benefitRepository.findByServiceId(serviceId)
                .map(benefit -> new BenefitView(benefit.getServiceId(), benefit.getName(),
                        benefitDetailRepository.existsById(benefit.getId())
                                ? DetailStatus.COLLECTED : DetailStatus.NOT_COLLECTED));
    }
}
