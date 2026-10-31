package kr.govbenefit.collection.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.domain.SourceEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수집 실행 결과를 지표로 내보낸다(결정 0037). 실행 수는 누적하고, 마지막 실행의 상태·시각·소요 시간·경로별 처리 건수는
 * 실행이 끝날 때마다 덮는다. 마지막 실행 지표는 첫 실행이 끝난 뒤에 생긴다. 재시작하면 다음 실행까지 없고,
 * 지난 값은 Prometheus에 남아 있다.
 */
@Component
@RequiredArgsConstructor
public class CollectionMetrics {

    /** 경로별 처리 건수 지표의 {@code result} 값 */
    private static final Map<String, ToIntFunction<CollectionRunStep>> RESULTS = Map.of(
            "pages", CollectionRunStep::getPageCount,
            "received", CollectionRunStep::getReceivedCount,
            "created", CollectionRunStep::getCreatedCount,
            "changed", CollectionRunStep::getChangedCount,
            "unchanged", CollectionRunStep::getUnchangedCount,
            "quarantined", CollectionRunStep::getQuarantinedCount,
            "unlinked", CollectionRunStep::getUnlinkedCount);

    private static final List<CollectionStatus> FINAL_STATUSES =
            List.of(CollectionStatus.SUCCEEDED, CollectionStatus.PARTIAL, CollectionStatus.FAILED);

    private final MeterRegistry registry;
    private final Clock clock;

    private volatile LastRun lastRun;

    /**
     * 끝난 실행 하나를 기록한다. 실행이 도중에 멈춰 시작하지 못한 경로의 건수는 0이다.
     */
    public void record(CollectionRun run, List<CollectionRunStep> steps) {
        Counter.builder("collection.runs")
                .description("끝난 수집 실행 수")
                .tag("status", run.getStatus().name())
                .register(registry)
                .increment();
        Map<SourceEndpoint, CollectionRunStep> stepByEndpoint = new EnumMap<>(SourceEndpoint.class);
        steps.forEach(step -> stepByEndpoint.put(step.getEndpoint(), step));
        lastRun = new LastRun(run.getStatus(),
                run.getFinishedAt().atZone(clock.getZone()).toEpochSecond(),
                Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis() / 1000.0,
                stepByEndpoint);
        registerLastRunGauges();
    }

    /**
     * 같은 이름·태그의 지표는 한 번만 등록되므로 실행마다 불러도 된다. 값은 등록할 때가 아니라 읽을 때 {@link #lastRun}에서 가져온다.
     */
    private void registerLastRunGauges() {
        for (CollectionStatus status : FINAL_STATUSES) {
            Gauge.builder("collection.last.run.status", this, m -> m.lastRun.status() == status ? 1 : 0)
                    .description("마지막 수집 실행의 상태면 1")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("collection.last.run.finished", this, m -> m.lastRun.finishedEpochSecond())
                .description("마지막 수집 실행이 끝난 시각(epoch)")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("collection.last.run.duration", this, m -> m.lastRun.durationSeconds())
                .description("마지막 수집 실행의 소요 시간")
                .baseUnit("seconds")
                .register(registry);
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            RESULTS.forEach((result, count) ->
                    Gauge.builder("collection.last.run.records", this, m -> m.lastRun.count(endpoint, count))
                            .description("마지막 수집 실행의 경로별 처리 건수")
                            .tags("endpoint", endpoint.name(), "result", result)
                            .register(registry));
        }
    }

    private record LastRun(CollectionStatus status, long finishedEpochSecond, double durationSeconds,
                           Map<SourceEndpoint, CollectionRunStep> steps) {

        int count(SourceEndpoint endpoint, ToIntFunction<CollectionRunStep> count) {
            CollectionRunStep step = steps.get(endpoint);
            return step == null ? 0 : count.applyAsInt(step);
        }
    }
}
