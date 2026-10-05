package kr.govbenefit.benefit.dto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import kr.govbenefit.common.api.ApiTimes;

/**
 * 저장된 정보가 오래됐는지(DEC-FRESHNESS). 혜택별 시각이 아니라 목록을 갱신한 마지막 수집 실행으로 판정하므로 모든 혜택에 같다.
 *
 * @param stale       기준 시각에서 48시간이 지났거나, 목록을 갱신한 실행이 없다.
 * @param referenceAt 목록을 갱신한 마지막 수집 실행({@code SUCCEEDED}·{@code PARTIAL})의 종료 시각. 그런 실행이 없으면 null이다.
 */
public record Freshness(boolean stale, OffsetDateTime referenceAt) {

    public static final Duration STALE_AFTER = Duration.ofHours(48);

    /**
     * 정확히 48시간이면 아직 오래되지 않았다.
     */
    public static Freshness judge(LocalDateTime referenceAt, LocalDateTime now) {
        if (referenceAt == null) {
            // 확인한 적이 없는 정보를 최신이라고 하지 않는다.
            return new Freshness(true, null);
        }
        return new Freshness(now.isAfter(referenceAt.plus(STALE_AFTER)), ApiTimes.toOffset(referenceAt));
    }
}
