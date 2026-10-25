package kr.govbenefit.region.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.region.domain.Region;
import kr.govbenefit.region.domain.Regions;
import kr.govbenefit.region.dto.RegionResponse;
import kr.govbenefit.region.dto.SidoOption;
import kr.govbenefit.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지역 목록과 조회(결정 0021, 0025). 지역은 243행이라 요청마다 전체를 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionService {

    private final RegionRepository regionRepository;

    /**
     * 시도 아래에 시군구를 묶는다. 순서는 시드의 표시 순서다. 프로필 선택지와 공개 지역 목록이 같이 쓴다.
     */
    public List<SidoOption> sidos() {
        List<Region> regions = regionRepository.findAllByOrderByDisplayOrderAsc();
        Map<String, List<RegionResponse>> sigungus = new HashMap<>();
        regions.stream().filter(region -> !region.isSido()).forEach(sigungu -> sigungus
                .computeIfAbsent(sigungu.getParentCode(), code -> new ArrayList<>()).add(RegionResponse.from(sigungu)));
        return regions.stream().filter(Region::isSido)
                .map(sido -> new SidoOption(sido.getCode(), sido.getName(),
                        List.copyOf(sigungus.getOrDefault(sido.getCode(), List.of()))))
                .toList();
    }

    public Regions regions() {
        return Regions.of(regionRepository.findAll());
    }
}
