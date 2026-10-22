package kr.govbenefit.match.domain;

import java.time.LocalDate;
import java.util.List;
import kr.govbenefit.profile.domain.Ages;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.UserProfile;

/**
 * 비교에 쓰는 프로필 값. null이 미입력이고, 속성 그룹의 {@code NONE}은 해당사항없음이다(결정 0022).
 *
 * @param age 기준일의 만 나이
 */
public record MatchSubject(
        Integer age,
        Gender gender,
        List<LifeAttribute> lifeAttributes,
        List<HouseholdAttribute> householdAttributes) {

    /**
     * @param profile 저장한 적이 없으면 null이며 모든 값이 미입력이다.
     */
    public static MatchSubject of(UserProfile profile, LocalDate today) {
        if (profile == null) {
            return new MatchSubject(null, null, null, null);
        }
        Integer age = profile.getBirthDate() == null ? null : Ages.fullAge(profile.getBirthDate(), today);
        return new MatchSubject(age, profile.getGender(), profile.lifeAttributes(), profile.householdAttributes());
    }
}
