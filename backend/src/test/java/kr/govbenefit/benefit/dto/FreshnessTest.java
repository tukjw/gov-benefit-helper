package kr.govbenefit.benefit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FreshnessTest {

    private static final LocalDateTime REFERENCE_AT = LocalDateTime.of(2026, 10, 3, 4, 12, 30, 123_456_000);

    @Test
    void 기준_시각에서_정확히_48시간이면_아직_오래되지_않았다() {
        assertThat(Freshness.judge(REFERENCE_AT, REFERENCE_AT.plusHours(48)).stale()).isFalse();
        assertThat(Freshness.judge(REFERENCE_AT, REFERENCE_AT.plusHours(48).minusMinutes(1)).stale()).isFalse();
    }

    @Test
    void 기준_시각에서_48시간이_지나면_오래된_정보다() {
        assertThat(Freshness.judge(REFERENCE_AT, REFERENCE_AT.plusHours(48).plusNanos(1_000)).stale()).isTrue();
        assertThat(Freshness.judge(REFERENCE_AT, REFERENCE_AT.plusDays(10)).stale()).isTrue();
    }

    @Test
    void 기준_시각은_오프셋을_붙여_준다() {
        Freshness freshness = Freshness.judge(REFERENCE_AT, REFERENCE_AT.plusHours(1));

        assertThat(freshness.referenceAt().toString()).isEqualTo("2026-10-03T04:12:30.123456+09:00");
    }

    @Test
    void 목록을_갱신한_실행이_없으면_오래된_정보이고_기준_시각이_없다() {
        Freshness freshness = Freshness.judge(null, REFERENCE_AT);

        assertThat(freshness.stale()).isTrue();
        assertThat(freshness.referenceAt()).isNull();
    }
}
