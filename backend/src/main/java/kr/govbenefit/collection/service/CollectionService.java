package kr.govbenefit.collection.service;

import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.govbenefit.collection.client.Gov24ApiException;
import kr.govbenefit.collection.client.Gov24CallBudget;
import kr.govbenefit.collection.client.Gov24Client;
import kr.govbenefit.collection.client.Gov24Page;
import kr.govbenefit.collection.client.Gov24Properties;
import kr.govbenefit.collection.domain.CollectionErrorType;
import kr.govbenefit.collection.domain.CollectionFailure;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.CollectionRunStep;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.domain.PageResult;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.repository.CollectionFailureRepository;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.collection.repository.CollectionRunStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 목록 → 상세 → 지원조건 전체를 쪽 단위로 받아 저장하고 실행 이력을 남긴다.
 * 이 메서드에는 트랜잭션을 두지 않는다. 실행·경로 기록과 쪽 저장이 각각 따로 커밋되어, 도중에 실패해도 그때까지의 결과가 남는다.
 * 실패한 쪽은 저장하지 않고 기록한 뒤 다음 쪽을 받는다. 상태 규칙은 결정 0007을 따른다.
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
    private final CollectionFailureRepository failureRepository;
    private final CollectionMetrics metrics;
    private final Clock clock;

    /**
     * 전체 수집을 한 번 실행하고 실행 ID를 돌려준다.
     * 목록 1쪽을 저장하지 못하면 실패, 그 밖에 실패한 쪽이 있거나 중단되면 부분 실패로 끝낸다.
     *
     * @throws IllegalStateException 인증키가 없을 때. 이때는 실행 기록을 만들지 않는다
     */
    public Long collectAll() {
        properties.requireServiceKey();
        CollectionRun run = runRepository.save(CollectionRun.start(now()));
        Gov24CallBudget budget = new Gov24CallBudget(properties.maxCallsPerRun());
        log.info("수집 시작 runId={}", run.getId());
        List<CollectionRunStep> steps = new ArrayList<>();
        RunOutcome outcome = collectEndpoints(run.getId(), budget, steps);
        run.finish(outcome.status(), outcome.errorType(), outcome.errorMessage(), now());
        // 저장보다 먼저 기록해, DB 장애로 실행 기록을 저장하지 못해도 실패가 지표에 남게 한다.
        metrics.record(run, steps);
        runRepository.save(run);
        switch (outcome.status()) {
            case SUCCEEDED -> log.info("수집 완료 runId={} calls={}", run.getId(), budget.used());
            case PARTIAL -> log.warn("수집 부분 실패 runId={} errorType={} calls={} message={}", run.getId(),
                    outcome.errorType(), budget.used(), outcome.errorMessage());
            default -> log.warn("수집 실패 runId={} errorType={} calls={} message={}", run.getId(),
                    outcome.errorType(), budget.used(), outcome.errorMessage());
        }
        return run.getId();
    }

    private RunOutcome collectEndpoints(Long runId, Gov24CallBudget budget, List<CollectionRunStep> steps) {
        try {
            boolean partial = false;
            for (SourceEndpoint endpoint : SourceEndpoint.values()) {
                EndpointProgress progress = collectEndpoint(runId, endpoint, budget, steps);
                if (endpoint == SourceEndpoint.SERVICE_LIST && !progress.firstPageDone) {
                    // 목록 1쪽이 없으면 이번 실행으로 갱신된 것이 없다.
                    return RunOutcome.stopped(CollectionStatus.FAILED, progress.stopCause);
                }
                if (progress.stopCause != null && progress.stopCause.type().abortsRun()) {
                    return RunOutcome.stopped(CollectionStatus.PARTIAL, progress.stopCause);
                }
                partial |= progress.status() != CollectionStatus.SUCCEEDED;
            }
            return new RunOutcome(partial ? CollectionStatus.PARTIAL : CollectionStatus.SUCCEEDED, null, null);
        } catch (DataAccessException | TransactionException | PersistenceException e) {
            // 실행·경로·실패 기록을 저장하지 못했다. DB를 쓸 수 없는 상태로 보고 끝낸다.
            log.error("수집 중 DB 오류 runId={} errorType={}", runId, CollectionErrorType.DB_ERROR, e);
            return new RunOutcome(CollectionStatus.FAILED, CollectionErrorType.DB_ERROR, describeDbError(e));
        } catch (RuntimeException e) {
            log.error("수집 중 예상하지 못한 오류 runId={} errorType={}", runId, CollectionErrorType.INTERNAL, e);
            return new RunOutcome(CollectionStatus.FAILED, CollectionErrorType.INTERNAL, e.getMessage());
        }
    }

    /**
     * @param steps 시작한 경로의 기록을 담는다. 실행이 끝난 뒤 지표로 남긴다
     */
    private EndpointProgress collectEndpoint(Long runId, SourceEndpoint endpoint, Gov24CallBudget budget,
                                             List<CollectionRunStep> steps) {
        CollectionRunStep step = stepRepository.save(CollectionRunStep.start(runId, endpoint, now()));
        steps.add(step);
        EndpointProgress progress = new EndpointProgress();
        try {
            int lastPage = 1;
            for (int page = 1; page <= lastPage && !progress.finished; page++) {
                PageOutcome outcome = collectPage(runId, endpoint, page, lastPage, budget);
                if (outcome.failure() != null) {
                    recordFailure(runId, endpoint, page, outcome.failure());
                    progress.addFailure(page, outcome.failure());
                    continue;
                }
                if (page == 1) {
                    progress.firstPageDone = true;
                }
                lastPage = outcome.lastPage();
                if (outcome.saved() != null) {
                    step.addPage(outcome.saved());
                    stepRepository.save(step);
                }
            }
            step.finish(progress.status(), now());
            log.info("수집 경로 종료 runId={} endpoint={} status={} pages={} received={} created={} changed={} "
                            + "unchanged={} quarantined={} unlinked={} failedPages={}", runId, endpoint,
                    step.getStatus(), step.getPageCount(), step.getReceivedCount(), step.getCreatedCount(),
                    step.getChangedCount(), step.getUnchangedCount(), step.getQuarantinedCount(),
                    step.getUnlinkedCount(), progress.failedPages);
            return progress;
        } catch (RuntimeException e) {
            step.finish(CollectionStatus.FAILED, now());
            throw e;
        } finally {
            stepRepository.save(step);
        }
    }

    /**
     * 한 쪽을 받아 저장한다. 받거나 저장하지 못하면 저장하지 않고 실패를 돌려준다.
     */
    private PageOutcome collectPage(Long runId, SourceEndpoint endpoint, int page, int lastPage,
                                    Gov24CallBudget budget) {
        String where = endpoint.path() + " page=" + page;
        int perPage = properties.perPage();
        Gov24Page response;
        try {
            response = gov24Client.fetchPage(endpoint, page, perPage, budget);
        } catch (Gov24ApiException e) {
            return PageOutcome.failed(new PageFailure(e.type(), e.attempts(), e.getMessage(), null));
        }
        if (response.data().isEmpty()) {
            // 1쪽이 비면 대상이 없는 것이다. 그 뒤 쪽이 비면 계산한 마지막 쪽보다 일찍 끝난 것이라 실패로 본다.
            return page == 1 ? new PageOutcome(0, null, null)
                    : PageOutcome.failed(new PageFailure(CollectionErrorType.API_TRUNCATED, response.attempts(),
                            where + " 마지막 쪽(" + lastPage + ") 전에 data가 비었습니다", null));
        }
        try {
            PageResult saved = pageWriter.write(runId, endpoint, page, response.data(), now());
            return new PageOutcome(Math.ceilDiv(response.totalCount(), perPage), saved, null);
        } catch (DataAccessException | TransactionException | PersistenceException e) {
            return PageOutcome.failed(new PageFailure(CollectionErrorType.DB_ERROR, response.attempts(),
                    where + " 저장 실패: " + describeDbError(e), e));
        }
    }

    private void recordFailure(Long runId, SourceEndpoint endpoint, int page, PageFailure failure) {
        log.warn("수집 쪽 실패 runId={} endpoint={} page={} errorType={} attempts={} message={}", runId, endpoint,
                page, failure.type(), failure.attempts(), failure.message(), failure.cause());
        failureRepository.save(CollectionFailure.of(runId, endpoint, page, failure.type(), failure.attempts(),
                failure.message(), now()));
    }

    /**
     * DB 예외 메시지에는 SQL과 값이 들어갈 수 있어 가장 안쪽 원인의 클래스 이름만 남긴다.
     */
    private static String describeDbError(RuntimeException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 경로 하나의 진행. 상태는 기록한 사실로 정한다.
     */
    private static final class EndpointProgress {

        private boolean firstPageDone;
        /** 남은 쪽을 더 받지 않는다. */
        private boolean finished;
        private int failedPages;
        /** 경로를 일찍 끝낸 실패. 1쪽 실패이거나 실행을 중단하는 실패다. */
        private PageFailure stopCause;

        void addFailure(int page, PageFailure failure) {
            failedPages++;
            if (page == 1 || failure.type().abortsRun()) {
                // 1쪽이 없으면 전체 건수를 몰라 더 받을 수 없다.
                stopCause = failure;
                finished = true;
            } else if (failure.type() == CollectionErrorType.API_TRUNCATED) {
                finished = true;
            }
        }

        CollectionStatus status() {
            if (!firstPageDone) {
                return CollectionStatus.FAILED;
            }
            return failedPages > 0 ? CollectionStatus.PARTIAL : CollectionStatus.SUCCEEDED;
        }
    }

    /**
     * @param lastPage 응답으로 계산한 마지막 쪽. 실패했으면 쓰지 않는다
     * @param saved    저장한 쪽의 건수. 빈 쪽이거나 실패했으면 null이다
     */
    private record PageOutcome(int lastPage, PageResult saved, PageFailure failure) {

        static PageOutcome failed(PageFailure failure) {
            return new PageOutcome(0, null, failure);
        }
    }

    /**
     * @param cause 로그에 스택을 남길 원인. API 실패는 메시지로 충분해 null이다
     */
    private record PageFailure(CollectionErrorType type, int attempts, String message, Throwable cause) {
    }

    private record RunOutcome(CollectionStatus status, CollectionErrorType errorType, String errorMessage) {

        static RunOutcome stopped(CollectionStatus status, PageFailure cause) {
            return new RunOutcome(status, cause.type(), cause.message());
        }
    }
}
