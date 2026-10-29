package kr.govbenefit.profile.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import kr.govbenefit.profile.dto.ProfileRequest;
import kr.govbenefit.profile.dto.ProfileResponse;
import kr.govbenefit.profile.repository.UserProfileRepository;
import kr.govbenefit.region.dto.RegionResponse;
import kr.govbenefit.region.repository.RegionRepository;
import kr.govbenefit.region.service.RegionService;
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
    private final RegionService regionService;
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

    @Transactional(readOnly = true)
    public ProfileOptionsResponse options() {
        return new ProfileOptionsResponse(regionService.sidos(), AttributeOption.of(LifeAttribute.class),
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
