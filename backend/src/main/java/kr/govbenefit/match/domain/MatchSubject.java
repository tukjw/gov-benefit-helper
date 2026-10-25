package kr.govbenefit.match.domain;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import kr.govbenefit.profile.domain.Ages;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.domain.UserProfile;

/**
 * 비교에 쓰는 프로필 값. null이 미입력이고, 속성 그룹의 {@code NONE}은 해당사항없음이다(결정 0022).
 *
 * @param age         기준일의 만 나이
 * @param sidoCode    거주 시도. 운영 기관 지역 비교에 쓴다.
 * @param sigunguCode 거주 시군구
 */
public record MatchSubject(
        Integer age,
        Gender gender,
        List<LifeAttribute> lifeAttributes,
        List<HouseholdAttribute> householdAttributes,
        String sidoCode,
        String sigunguCode) {

    /**
     * @param profile 저장한 적이 없으면 null이며 모든 값이 미입력이다.
     */
    public static MatchSubject of(UserProfile profile, LocalDate today) {
        if (profile == null) {
            return new MatchSubject(null, null, null, null, null, null);
        }
        Integer age = profile.getBirthDate() == null ? null : Ages.fullAge(profile.getBirthDate(), today);
        return new MatchSubject(age, profile.getGender(), profile.lifeAttributes(), profile.householdAttributes(),
                profile.getSidoCode(), profile.getSigunguCode());
    }

    /**
     * 저장하지 않은 프로필 값. 속성은 저장된 프로필({@link UserProfile#lifeAttributes()})과 같은 enum 선언 순서로 둔다.
     * 순서가 다르면 같은 값인데 상세 비교 응답이 달라진다.
     */
    public static MatchSubject of(ProfileValues values, LocalDate today) {
        Integer age = values.birthDate() == null ? null : Ages.fullAge(values.birthDate(), today);
        return new MatchSubject(age, values.gender(), ordered(LifeAttribute.class, values.lifeAttributes()),
                ordered(HouseholdAttribute.class, values.householdAttributes()), values.sidoCode(),
                values.sigunguCode());
    }

    private static <E extends Enum<E>> List<E> ordered(Class<E> type, Set<E> values) {
        return values == null ? null
                : Arrays.stream(type.getEnumConstants()).filter(values::contains).toList();
    }
}
