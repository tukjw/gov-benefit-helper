package kr.govbenefit.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SendRateLimitTest {

    private static final SendRateLimit LIMIT = new SendRateLimit(Duration.ofSeconds(60), Duration.ofHours(24), 10);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 10, 0);

    @Test
    void 보낸_적이_없으면_보낼_수_있다() {
        assertThat(LIMIT.retryAfter(NOW, Optional.empty(), 0, Optional.empty())).isEmpty();
    }

    @Test
    void 마지막_발송에서_60초가_지나야_다시_보낸다() {
        assertThat(LIMIT.retryAfter(NOW, Optional.of(NOW.minusSeconds(59)), 1, Optional.of(NOW.minusSeconds(59))))
                .contains(Duration.ofSeconds(1));
        assertThat(LIMIT.retryAfter(NOW, Optional.of(NOW.minusSeconds(60)), 1, Optional.of(NOW.minusSeconds(60))))
                .isEmpty();
    }

    @Test
    void 최근_24시간에_10번_보냈으면_가장_오래된_발송이_창을_벗어날_때까지_기다린다() {
        LocalDateTime oldest = NOW.minusHours(3);
        LocalDateTime last = NOW.minusMinutes(5);

        assertThat(LIMIT.retryAfter(NOW, Optional.of(last), 9, Optional.of(oldest))).isEmpty();
        assertThat(LIMIT.retryAfter(NOW, Optional.of(last), 10, Optional.of(oldest))).contains(Duration.ofHours(21));
    }

    @Test
    void 간격과_상한에_모두_걸리면_더_긴_쪽을_기다린다() {
        LocalDateTime oldest = NOW.minusHours(24).plusSeconds(30);
        LocalDateTime last = NOW.minusSeconds(10);

        assertThat(LIMIT.retryAfter(NOW, Optional.of(last), 10, Optional.of(oldest))).contains(Duration.ofSeconds(50));
    }

    @Test
    void 창은_지금부터_24시간_전까지다() {
        assertThat(LIMIT.windowStart(NOW)).isEqualTo(NOW.minusHours(24));
    }
}
