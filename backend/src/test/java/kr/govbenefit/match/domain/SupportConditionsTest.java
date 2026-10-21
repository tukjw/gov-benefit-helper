package kr.govbenefit.match.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SupportConditionsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 문자열_Y만_Y로_읽는다() {
        SupportConditions conditions = parse("""
                {"JA0101": "Y", "JA0102": null, "JA0302": "y", "JA0303": "N", "JA0328": true,
                 "JA0401": "Y", "JA0410": "Y", "JA0201": "Y", "JA1101": "Y"}""");

        assertThat(conditions.yesCodes()).containsExactlyInAnyOrder("JA0101", "JA0401", "JA0410");
        assertThat(conditions.allowedGenders()).containsExactly(Gender.MALE);
        assertThat(conditions.included(LifeAttribute.values())).isEmpty();
        // 선언 순서이고 해당사항없음이 마지막이다.
        assertThat(conditions.included(HouseholdAttribute.values()))
                .containsExactly(HouseholdAttribute.MULTICULTURAL_FAMILY, HouseholdAttribute.NONE);
    }

    @Test
    void 연령은_정수만_값으로_읽는다() {
        assertThat(parse("{\"JA0110\": 19, \"JA0111\": 39}"))
                .extracting(SupportConditions::ageStart, SupportConditions::ageEnd).containsExactly(19, 39);
        assertThat(parse("{\"JA0110\": null}"))
                .extracting(SupportConditions::ageStart, SupportConditions::ageEnd).containsExactly(null, null);
        assertThat(parse("{\"JA0110\": \"19\", \"JA0111\": 39.5}"))
                .extracting(SupportConditions::ageStart, SupportConditions::ageEnd).containsExactly(null, null);
        assertThat(parse("{\"JA0110\": 99999999999}").ageStart()).isNull();
    }

    @Test
    void 객체가_아닌_원문은_모든_코드가_없다() {
        SupportConditions conditions = parse("[]");

        assertThat(conditions.yesCodes()).isEmpty();
        assertThat(conditions.ageStart()).isNull();
        assertThat(conditions.allowedGenders()).isEqualTo(List.of());
    }

    private SupportConditions parse(String json) {
        return SupportConditions.parse(jsonMapper.readTree(json));
    }
}
