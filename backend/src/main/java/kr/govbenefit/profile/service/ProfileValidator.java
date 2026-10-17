package kr.govbenefit.profile.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import kr.govbenefit.common.api.InvalidInputException;
import kr.govbenefit.profile.domain.Ages;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.dto.ProfileRequest;
import kr.govbenefit.region.domain.Region;
import kr.govbenefit.region.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로필 저장 요청의 항목별 검증(REQ-PROFILE-01). 모든 항목을 검사해 오류를 모은 뒤 한 번에 거절한다.
 */
@Component
@RequiredArgsConstructor
public class ProfileValidator {

    /** 지원조건 연령 코드의 상한과 같다. */
    static final int MAX_AGE = 120;

    private static final List<String> LIFE_VALUES = Arrays.stream(LifeAttribute.values()).map(Enum::name).toList();
    private static final List<String> HOUSEHOLD_VALUES =
            Arrays.stream(HouseholdAttribute.values()).map(Enum::name).toList();

    private static final DateTimeFormatter BIRTH_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final RegionRepository regionRepository;
    private final Clock clock;

    /**
     * @throws InvalidInputException 항목 오류가 하나라도 있으면 모든 오류를 담아 던진다
     */
    public ProfileValues validate(ProfileRequest request) {
        List<InputError> errors = new ArrayList<>();
        LocalDate birthDate = birthDate(request.birthDate(), errors);
        Gender gender = gender(request.gender(), errors);
        Region sido = sido(request.sidoCode(), errors);
        Region sigungu = sigungu(request.sigunguCode(), request.sidoCode(), sido, errors);
        checkAttributes("lifeAttributes", request.lifeAttributes(), LIFE_VALUES, errors);
        checkAttributes("householdAttributes", request.householdAttributes(), HOUSEHOLD_VALUES, errors);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Set<LifeAttribute> life = request.lifeAttributes() == null ? null
                : request.lifeAttributes().stream().map(LifeAttribute::valueOf).collect(Collectors.toSet());
        Set<HouseholdAttribute> household = request.householdAttributes() == null ? null
                : request.householdAttributes().stream().map(HouseholdAttribute::valueOf).collect(Collectors.toSet());
        return new ProfileValues(birthDate, gender, code(sido), code(sigungu), life, household);
    }

    private LocalDate birthDate(String value, List<InputError> errors) {
        if (value == null) {
            return null;
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(value, BIRTH_DATE);
        } catch (DateTimeParseException ex) {
            errors.add(new InputError("birthDate", value, "yyyy-MM-dd 형식의 실제 날짜여야 합니다."));
            return null;
        }
        LocalDate today = LocalDate.now(clock);
        if (birthDate.isAfter(today)) {
            errors.add(new InputError("birthDate", value, "미래 날짜는 입력할 수 없습니다."));
        } else if (Ages.fullAge(birthDate, today) > MAX_AGE) {
            errors.add(new InputError("birthDate", value, "만 " + MAX_AGE + "세까지 입력할 수 있습니다."));
        }
        return birthDate;
    }

    private static Gender gender(String value, List<InputError> errors) {
        if (value == null) {
            return null;
        }
        for (Gender gender : Gender.values()) {
            if (gender.name().equals(value)) {
                return gender;
            }
        }
        errors.add(new InputError("gender", value, "MALE 또는 FEMALE이어야 합니다."));
        return null;
    }

    private Region sido(String code, List<InputError> errors) {
        if (code == null) {
            return null;
        }
        Optional<Region> region = regionRepository.findById(code).filter(Region::isSido);
        if (region.isEmpty()) {
            errors.add(new InputError("sidoCode", code, "지원하지 않는 시도 코드입니다."));
        }
        return region.orElse(null);
    }

    /**
     * 시도가 틀렸으면 시군구는 코드가 있는지만 본다. 소속 오류를 함께 내면 원인이 하나인데 오류가 둘이 된다.
     */
    private Region sigungu(String code, String sidoCode, Region sido, List<InputError> errors) {
        if (code == null) {
            return null;
        }
        if (sidoCode == null) {
            errors.add(new InputError("sigunguCode", code, "시도와 함께 입력해야 합니다."));
            return null;
        }
        Optional<Region> region = regionRepository.findById(code).filter(found -> !found.isSido());
        if (region.isEmpty()) {
            errors.add(new InputError("sigunguCode", code, "지원하지 않는 시군구 코드입니다."));
            return null;
        }
        if (sido != null && !sido.getCode().equals(region.get().getParentCode())) {
            errors.add(new InputError("sigunguCode", code, "선택한 시도의 시군구가 아닙니다."));
            return null;
        }
        return region.get();
    }

    /**
     * null은 미입력, NONE 하나는 해당 없음이다. 빈 배열은 둘 중 무엇인지 알 수 없어 거절한다(결정 0022).
     */
    private static void checkAttributes(String field, List<String> values, List<String> allowed,
                                        List<InputError> errors) {
        if (values == null) {
            return;
        }
        if (values.isEmpty()) {
            errors.add(new InputError(field, "", "미입력이면 null, 해당 없음이면 [\"NONE\"]을 보내야 합니다."));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || !allowed.contains(value)) {
                errors.add(new InputError(field, value, "지원하지 않는 값입니다."));
            } else if (!seen.add(value)) {
                errors.add(new InputError(field, value, "같은 값을 두 번 보낼 수 없습니다."));
            }
        }
        if (seen.contains("NONE") && seen.size() > 1) {
            errors.add(new InputError(field, String.join(",", values),
                    "해당 없음(NONE)은 다른 값과 함께 고를 수 없습니다."));
        }
    }

    private static String code(Region region) {
        return region == null ? null : region.getCode();
    }
}
