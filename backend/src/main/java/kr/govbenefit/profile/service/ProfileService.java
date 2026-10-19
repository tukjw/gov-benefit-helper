package kr.govbenefit.profile.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.ApiTimes;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.profile.domain.Ages;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.domain.UserProfile;
import kr.govbenefit.profile.dto.ProfileOptionsResponse;
import kr.govbenefit.profile.dto.ProfileOptionsResponse.AttributeOption;
import kr.govbenefit.profile.dto.ProfileOptionsResponse.SidoOption;
import kr.govbenefit.profile.dto.ProfileRequest;
import kr.govbenefit.profile.dto.ProfileResponse;
import kr.govbenefit.profile.dto.RegionResponse;
import kr.govbenefit.profile.repository.UserProfileRepository;
import kr.govbenefit.region.domain.Region;
import kr.govbenefit.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최소 프로필의 저장·조회(REQ-PROFILE-01, DEC-PROFILE). 사용자는 토큰으로만 정한다(결정 0018).
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserAccountRepository userAccountRepository;
    private final RegionRepository regionRepository;
    private final ProfileValidator profileValidator;
    private final Clock clock;

    /**
     * 저장한 적이 없으면 모든 값이 null인 프로필이다. 조건 비교에서 미입력은 프로필이 없는 것과 같게 다루므로 나누지 않는다.
     */
    @Transactional(readOnly = true)
    public ProfileResponse get(Long userId) {
        if (!userAccountRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return userProfileRepository.findById(userId).map(this::toResponse).orElseGet(ProfileResponse::empty);
    }

    /**
     * 프로필 전체를 바꾼다. 처음이면 만든다. 계정 행을 잠가 같은 사용자의 저장을 직렬화한다. 첫 저장이 동시에 와도 행이 하나만
     * 생기고 나중 요청이 덮어쓴다.
     */
    @Transactional
    public ProfileResponse put(Long userId, ProfileRequest request) {
        ProfileValues values = profileValidator.validate(request);
        if (userAccountRepository.findByIdForUpdate(userId).isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> userProfileRepository.save(UserProfile.create(userId, now)));
        profile.replace(values, now);
        return toResponse(profile);
    }

    /**
     * 지역 전체(243행)를 한 번 읽어 시도 아래에 시군구를 묶는다. 순서는 시드의 표시 순서다.
     */
    @Transactional(readOnly = true)
    public ProfileOptionsResponse options() {
        List<Region> regions = regionRepository.findAllByOrderByDisplayOrderAsc();
        Map<String, List<RegionResponse>> sigungus = new HashMap<>();
        regions.stream().filter(region -> !region.isSido()).forEach(sigungu -> sigungus
                .computeIfAbsent(sigungu.getParentCode(), code -> new ArrayList<>()).add(RegionResponse.from(sigungu)));
        List<SidoOption> options = regions.stream().filter(Region::isSido)
                .map(sido -> new SidoOption(sido.getCode(), sido.getName(),
                        List.copyOf(sigungus.getOrDefault(sido.getCode(), List.of()))))
                .toList();
        return new ProfileOptionsResponse(options, AttributeOption.of(LifeAttribute.class),
                AttributeOption.of(HouseholdAttribute.class));
    }

    private ProfileResponse toResponse(UserProfile profile) {
        LocalDate birthDate = profile.getBirthDate();
        Integer age = birthDate == null ? null : Ages.fullAge(birthDate, LocalDate.now(clock));
        return new ProfileResponse(birthDate, age, profile.getGender(), region(profile.getSidoCode()),
                region(profile.getSigunguCode()), profile.lifeAttributes(), profile.householdAttributes(),
                ApiTimes.toOffset(profile.getUpdatedAt()));
    }

    private RegionResponse region(String code) {
        return code == null ? null : regionRepository.findById(code).map(RegionResponse::from).orElseThrow();
    }
}
