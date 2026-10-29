package kr.govbenefit.match.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.domain.UserProfile;
import org.junit.jupiter.api.Test;

/**
 * 요청 값으로 만든 비교 대상이 같은 값을 저장한 프로필로 만든 것과 같은지 검증한다. 같아야 비회원과 로그인 사용자의 결과가 같다.
 */
class MatchSubjectTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void 요청_값은_저장된_프로필과_같은_비교_대상이다() {
        // 선언 순서와 반대로 넣어도 저장된 프로필처럼 선언 순서다.
        ProfileValues values = new ProfileValues(LocalDate.of(1990, 10, 21), Gender.FEMALE, "6470000", "5110000",
                new LinkedHashSet<>(List.of(LifeAttribute.JOB_SEEKER, LifeAttribute.FARMER, LifeAttribute.PREGNANT)),
                new LinkedHashSet<>(List.of(HouseholdAttribute.SINGLE_PERSON_HOUSEHOLD,
                        HouseholdAttribute.MULTICULTURAL_FAMILY)));

        MatchSubject subject = MatchSubject.of(values, TODAY);

        assertThat(subject).isEqualTo(MatchSubject.of(stored(values), TODAY));
        assertThat(subject.age()).isEqualTo(35);
        assertThat(subject.lifeAttributes())
                .containsExactly(LifeAttribute.PREGNANT, LifeAttribute.FARMER, LifeAttribute.JOB_SEEKER);
    }

    @Test
    void 해당_없음과_미입력은_저장된_프로필과_같게_구분된다() {
        ProfileValues values = new ProfileValues(null, null, "6470000", null, Set.of(LifeAttribute.NONE), null);

        MatchSubject subject = MatchSubject.of(values, TODAY);

        assertThat(subject).isEqualTo(MatchSubject.of(stored(values), TODAY));
        assertThat(subject.lifeAttributes()).containsExactly(LifeAttribute.NONE);
        assertThat(subject.householdAttributes()).isNull();
        assertThat(subject.age()).isNull();
    }

    @Test
    void 값이_하나도_없으면_프로필이_없는_것과_같다() {
        assertThat(MatchSubject.of(ProfileValues.empty(), TODAY)).isEqualTo(MatchSubject.of((UserProfile) null, TODAY));
    }

    private static UserProfile stored(ProfileValues values) {
        LocalDateTime now = TODAY.atStartOfDay();
        UserProfile profile = UserProfile.create(1L, now);
        profile.replace(values, now);
        return profile;
    }
}
