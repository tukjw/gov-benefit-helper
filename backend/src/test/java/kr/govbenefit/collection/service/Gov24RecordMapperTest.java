package kr.govbenefit.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitDetailContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class Gov24RecordMapperTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 등록일시와_수정일시를_출처_시각으로_해석한다() {
        ObjectNode record = record();
        record.put("등록일시", "20210923123456");
        record.put("수정일시", "20260914114346");
        record.put("신청기한", "상시신청");

        assertThat(Gov24RecordMapper.toBenefitNormalization(record)).isEqualTo(new BenefitNormalization(
                LocalDateTime.of(2021, 9, 23, 12, 34, 56),
                LocalDateTime.of(2026, 9, 14, 11, 43, 46),
                ApplyPeriodRule.ALWAYS));
    }

    @Test
    void 출처_시각이_null이거나_없으면_null이다() {
        ObjectNode record = record();
        record.putNull("수정일시");

        BenefitNormalization normalization = Gov24RecordMapper.toBenefitNormalization(record);

        assertThat(normalization.sourceModifiedAt()).isNull();
        assertThat(normalization.sourceRegisteredAt()).isNull();
        assertThat(normalization.applyPeriodRule()).isEqualTo(ApplyPeriodRule.NO_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-14", "20260914", "20260230120000", "20260914246000", "", "  "})
    void 형식이_다르거나_없는_시각이면_null이다(String value) {
        ObjectNode record = record();
        record.put("수정일시", value);

        assertThat(Gov24RecordMapper.toBenefitNormalization(record).sourceModifiedAt()).isNull();
    }

    @Test
    void 문자열이_아닌_시각은_null이다() {
        ObjectNode record = record();
        record.put("수정일시", 20260914114346L);

        assertThat(Gov24RecordMapper.toBenefitNormalization(record).sourceModifiedAt()).isNull();
    }

    @Test
    void 빈_문자열과_공백뿐인_문자열은_null로_옮기고_나머지는_그대로_둔다() {
        ObjectNode list = record();
        list.put("선정기준", "");
        list.put("지원대상", "   ");
        list.putNull("소관기관명");
        list.put("신청기한", " - 주민센터 : 1월~2월경 ");
        ObjectNode detail = record();
        detail.put("자치법규", "\t");
        detail.put("구비서류", " 신분증 ");

        BenefitContent content = Gov24RecordMapper.toBenefitContent(list);
        BenefitDetailContent detailContent = Gov24RecordMapper.toBenefitDetailContent(detail);

        assertThat(content.selectionCriteria()).isNull();
        assertThat(content.target()).isNull();
        assertThat(content.agencyName()).isNull();
        assertThat(content.applyPeriodText()).isEqualTo(" - 주민센터 : 1월~2월경 ");
        assertThat(detailContent.localLaws()).isNull();
        assertThat(detailContent.requiredDocuments()).isEqualTo(" 신분증 ");
    }

    private ObjectNode record() {
        ObjectNode record = jsonMapper.createObjectNode();
        record.put("서비스ID", "511000000138");
        return record;
    }
}
