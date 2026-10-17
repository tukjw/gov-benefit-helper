package kr.govbenefit.profile.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 검증을 마친 프로필 값. 모든 값은 선택 입력이라 null이 미입력이다. 속성 그룹은 null이 미입력, {@code NONE} 하나만 있으면
 * 해당 없음이며 빈 집합은 없다(결정 0022).
 */
public record ProfileValues(
        LocalDate birthDate,
        Gender gender,
        String sidoCode,
        String sigunguCode,
        Set<LifeAttribute> lifeAttributes,
        Set<HouseholdAttribute> householdAttributes) {

    public static ProfileValues empty() {
        return new ProfileValues(null, null, null, null, null, null);
    }
}
