package kr.govbenefit.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 속성 값과 지원조건 코드의 대응(결정 0022). 코드표는 데이터 검증 보고서 §5의 생애·직업·건강(JA03xx)과 가구(JA04xx)다.
 */
class ProfileAttributeTest {

    @Test
    void 생애_속성은_JA03_코드표의_17개와_같다() {
        assertThat(codes(LifeAttribute.values())).containsExactlyEntriesOf(ordered(
                "JA0301", "예비부모/난임", "JA0302", "임산부", "JA0303", "출산/입양", "JA0313", "농업인",
                "JA0314", "어업인", "JA0315", "축산업인", "JA0316", "임업인", "JA0317", "초등학생",
                "JA0318", "중학생", "JA0319", "고등학생", "JA0320", "대학생/대학원생", "JA0326", "근로자/직장인",
                "JA0327", "구직자/실업자", "JA0328", "장애인", "JA0329", "국가보훈대상자", "JA0330", "질병/질환자",
                "JA0322", "해당사항없음"));
        assertThat(LifeAttribute.NONE.jaCode()).isEqualTo("JA0322");
    }

    @Test
    void 가구_속성은_JA04_코드표의_9개와_같다() {
        assertThat(codes(HouseholdAttribute.values())).containsExactlyEntriesOf(ordered(
                "JA0401", "다문화가족", "JA0402", "북한이탈주민", "JA0403", "한부모가정/조손가정", "JA0404", "1인가구",
                "JA0411", "다자녀가구", "JA0412", "무주택세대", "JA0413", "신규전입", "JA0414", "확대가족",
                "JA0410", "해당사항없음"));
        assertThat(HouseholdAttribute.NONE.jaCode()).isEqualTo("JA0410");
    }

    @Test
    void 해당사항없음만_NONE이고_선언_순서의_마지막이다() {
        assertThat(Arrays.stream(LifeAttribute.values()).filter(ProfileAttribute::isNone)).containsExactly(LifeAttribute.NONE);
        assertThat(Arrays.stream(HouseholdAttribute.values()).filter(ProfileAttribute::isNone))
                .containsExactly(HouseholdAttribute.NONE);
        assertThat(LifeAttribute.values()[LifeAttribute.values().length - 1]).isEqualTo(LifeAttribute.NONE);
        assertThat(HouseholdAttribute.values()[HouseholdAttribute.values().length - 1]).isEqualTo(HouseholdAttribute.NONE);
    }

    @Test
    void 코드로_값을_찾고_다른_그룹의_코드는_찾지_않는다() {
        assertThat(LifeAttribute.fromJaCode("JA0302")).contains(LifeAttribute.PREGNANT);
        assertThat(LifeAttribute.fromJaCode("JA0404")).isEmpty();
        assertThat(HouseholdAttribute.fromJaCode("JA0404")).contains(HouseholdAttribute.SINGLE_PERSON_HOUSEHOLD);
        assertThat(HouseholdAttribute.fromJaCode("JA0302")).isEmpty();
    }

    @Test
    void 두_그룹의_코드는_겹치지_않는다() {
        long distinct = Stream.concat(Arrays.stream(LifeAttribute.values()), Arrays.stream(HouseholdAttribute.values()))
                .map(ProfileAttribute::jaCode).distinct().count();

        assertThat(distinct).isEqualTo(LifeAttribute.values().length + HouseholdAttribute.values().length);
    }

    private static Map<String, String> codes(ProfileAttribute[] attributes) {
        return Arrays.stream(attributes).collect(Collectors.toMap(ProfileAttribute::jaCode, ProfileAttribute::label,
                (a, b) -> a, LinkedHashMap::new));
    }

    private static Map<String, String> ordered(String... codeLabels) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < codeLabels.length; i += 2) {
            map.put(codeLabels[i], codeLabels[i + 1]);
        }
        return map;
    }
}
