package kr.govbenefit.collection.service;

import java.time.Clock;
import java.time.LocalDateTime;
import kr.govbenefit.collection.client.Gov24Client;
import kr.govbenefit.collection.client.Gov24Page;
import kr.govbenefit.collection.client.Gov24Properties;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.collection.repository.CollectionRunStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 목록 → 상세 → 지원조건 전체를 쪽 단위로 받아 저장하고 실행 이력을 남긴다.
 * 이 메서드에는 트랜잭션을 두지 않는다. 실행·경로 기록과 쪽 저장이 각각 따로 커밋되어, 도중에 실패해도 그때까지의 결과가 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionService {

    private final Gov24Client gov24Client;
    private final Gov24Properties properties;
    private final CollectionPageWriter pageWriter;
    private final CollectionRunRepository runRepository;
    private final CollectionRunStepRepository stepRepository;
    private final Clock clock;

    /**
     * 전체 수집을 한 번 실행하고 실행 ID를 돌려준다. 요청이 하나라도 실패하면 이후 경로는 받지 않고 실행을 실패로 끝낸다.
     *
     * @throws IllegalStateException 인증키가 없을 때. 이때는 실행 기록을 만들지 않는다
     */
    public Long collectAll() {
        properties.requireServiceKey();
        CollectionRun run = runRepository.save(CollectionRun.start(now()));
        log.info("수집 시작 runId={}", run.getId());
        try {
            for (SourceEndpoint endpoint : SourceEndpoint.values()) {
                collectEndpoint(run.getId(), endpoint);
            }
            run.succeed(now());
            log.info("수집 완료 runId={}", run.getId());
        } catch (RuntimeException e) {
            run.fail(now(), e.getMessage());
            log.warn("수집 실패 runId={}", run.getId(), e);
        }
        runRepository.save(run);
        return run.getId();
    }

    private void collectEndpoint(Long runId, SourceEndpoint endpoint) {
        CollectionRunStep step = stepRepository.save(CollectionRunStep.start(runId, endpoint, now()));
        try {
            int perPage = properties.perPage();
            int lastPage = 1;
            for (int page = 1; page <= lastPage; page++) {
                Gov24Page response = gov24Client.fetchPage(endpoint, page, perPage);
                if (response.data().isEmpty()) {
                    break;
                }
                lastPage = Math.ceilDiv(response.totalCount(), perPage);
                step.addPage(pageWriter.write(runId, endpoint, page, response.data(), now()));
                step = stepRepository.save(step);
            }
            step.succeed(now());
            log.info("수집 경로 완료 runId={} endpoint={} pages={} received={} created={} changed={} unchanged={} "
                            + "quarantined={} unlinked={}", runId, endpoint, step.getPageCount(),
                    step.getReceivedCount(), step.getCreatedCount(), step.getChangedCount(),
                    step.getUnchangedCount(), step.getQuarantinedCount(), step.getUnlinkedCount());
        } catch (RuntimeException e) {
            step.fail(now());
            throw e;
        } finally {
            stepRepository.save(step);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
