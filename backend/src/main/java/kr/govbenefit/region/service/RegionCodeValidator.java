package kr.govbenefit.region.service;

import java.util.List;
import java.util.Optional;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import kr.govbenefit.region.domain.Region;
import kr.govbenefit.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시도·시군구 코드 검증(결정 0021). 프로필 저장과 혜택 검색의 운영 기관 지역 필터가 같은 기준을 쓴다.
 * 시군구는 시도와 함께, 그 시도의 시군구만 받는다. 오류는 호출한 쪽의 필드 이름으로 모은다.
 */
@Component
@RequiredArgsConstructor
public class RegionCodeValidator {

    private final RegionRepository regionRepository;

    /**
     * 검증한 시도·시군구. 입력이 없거나 오류가 있으면 그 값은 null이다.
     */
    public record RegionSelection(Region sido, Region sigungu) {
    }

    public RegionSelection validate(String sidoField, String sidoCode, String sigunguField, String sigunguCode,
                                    List<InputError> errors) {
        Region sido = sido(sidoField, sidoCode, errors);
        Region sigungu = sigungu(sigunguField, sigunguCode, sidoCode, sido, errors);
        return new RegionSelection(sido, sigungu);
    }

    private Region sido(String field, String code, List<InputError> errors) {
        if (code == null) {
            return null;
        }
        Optional<Region> region = regionRepository.findById(code).filter(Region::isSido);
        if (region.isEmpty()) {
            errors.add(new InputError(field, code, "지원하지 않는 시도 코드입니다."));
        }
        return region.orElse(null);
    }

    /**
     * 시도가 틀렸으면 시군구는 코드가 있는지만 본다. 소속 오류를 함께 내면 원인이 하나인데 오류가 둘이 된다.
     */
    private Region sigungu(String field, String code, String sidoCode, Region sido, List<InputError> errors) {
        if (code == null) {
            return null;
        }
        if (sidoCode == null) {
            errors.add(new InputError(field, code, "시도와 함께 입력해야 합니다."));
            return null;
        }
        Optional<Region> region = regionRepository.findById(code).filter(found -> !found.isSido());
        if (region.isEmpty()) {
            errors.add(new InputError(field, code, "지원하지 않는 시군구 코드입니다."));
            return null;
        }
        if (sido != null && !sido.getCode().equals(region.get().getParentCode())) {
            errors.add(new InputError(field, code, "선택한 시도의 시군구가 아닙니다."));
            return null;
        }
        return region.get();
    }
}
