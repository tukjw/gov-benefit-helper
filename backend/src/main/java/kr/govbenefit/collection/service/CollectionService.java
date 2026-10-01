package kr.govbenefit.collection.service;

import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.LocalDateTime;
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
        CollectionStatus status = CollectionStatus.SUCCEEDED;
        CollectionErrorType errorType = null;
        String errorMessage = null;
        try {
            for (SourceEndpoint endpoint : SourceEndpoint.values()) {
                StepResult result = collectEndpoint(run.getId(), endpoint, budget);
                if (endpoint == SourceEndpoint.SERVICE_LIST && result.status() == CollectionStatus.FAILED) {
                    status = CollectionStatus.FAILED;
                    errorType = result.failure().type();
                    errorMessage = result.failure().message();
                    break;
                }
                if (result.status() != CollectionStatus.SUCCEEDED) {
                    status = CollectionStatus.PARTIAL;
                }
            }
        } catch (RunAborted e) {
            // 목록 1쪽에서 중단되면 이번 실행으로 갱신된 것이 없다.
            status = e.endpoint == SourceEndpoint.SERVICE_LIST && e.page == 1
                    ? CollectionStatus.FAILED : CollectionStatus.PARTIAL;
            errorType = e.type;
            errorMessage = e.getMessage();
        } catch (DataAccessException | TransactionException | PersistenceException e) {
            status = CollectionStatus.FAILED;
            errorType = CollectionErrorType.DB_ERROR;
            errorMessage = describeDbError(e);
            log.error("수집 중 DB 오류 runId={} errorType={}", run.getId(), errorType, e);
        } catch (RuntimeException e) {
            status = CollectionStatus.FAILED;
            errorType = CollectionErrorType.INTERNAL;
            errorMessage = e.getMessage();
            log.error("수집 중 예상하지 못한 오류 runId={} errorType={}", run.getId(), errorType, e);
        }
        run.finish(status, errorType, errorMessage, now());
        runRepository.save(run);
        switch (status) {
            case SUCCEEDED -> log.info("수집 완료 runId={} calls={}", run.getId(), budget.used());
            case PARTIAL -> log.warn("수집 부분 실패 runId={} errorType={} calls={} message={}", run.getId(),
                    errorType, budget.used(), errorMessage);
            default -> log.warn("수집 실패 runId={} errorType={} calls={} message={}", run.getId(), errorType,
                    budget.used(), errorMessage);
        }
        return run.getId();
    }

    private StepResult collectEndpoint(Long runId, SourceEndpoint endpoint, Gov24CallBudget budget) {
        CollectionRunStep step = stepRepository.save(CollectionRunStep.start(runId, endpoint, now()));
        CollectionStatus status = CollectionStatus.SUCCEEDED;
        PageFailure failure = null;
        int page = 1;
        try {
            int perPage = properties.perPage();
            int lastPage = 1;
            for (; page <= lastPage; page++) {
                Gov24Page response;
                try {
                    response = gov24Client.fetchPage(endpoint, page, perPage, budget);
                } catch (Gov24ApiException e) {
                    PageFailure pageFailure = recordFailure(runId, endpoint, page, e.type(), e.attempts(),
                            e.getMessage(), null);
                    if (e.type().abortsRun()) {
                        throw new RunAborted(endpoint, page, e.type(), e.getMessage());
                    }
                    if (page == 1) {
                        // 전체 건수를 모르므로 이 경로는 더 받을 수 없다.
                        status = CollectionStatus.FAILED;
                        failure = pageFailure;
                        break;
                    }
                    status = CollectionStatus.PARTIAL;
                    continue;
                }
                if (response.data().isEmpty()) {
                    if (page > 1) {
                        // 계산한 마지막 쪽보다 일찍 끝났다. 끝으로 보면 빠진 쪽이 성공으로 기록된다.
                        recordFailure(runId, endpoint, page, CollectionErrorType.API_TRUNCATED, response.attempts(),
                                endpoint.path() + " page=" + page + " 마지막 쪽(" + lastPage + ") 전에 data가 비었습니다",
                                null);
                        status = CollectionStatus.PARTIAL;
                    }
                    break;
                }
                lastPage = Math.ceilDiv(response.totalCount(), perPage);
                try {
                    step.addPage(pageWriter.write(runId, endpoint, page, response.data(), now()));
                } catch (DataAccessException | TransactionException | PersistenceException e) {
                    PageFailure pageFailure = recordFailure(runId, endpoint, page, CollectionErrorType.DB_ERROR,
                            response.attempts(), endpoint.path() + " page=" + page + " 저장 실패: "
                                    + describeDbError(e), e);
                    if (page == 1) {
                        status = CollectionStatus.FAILED;
                        failure = pageFailure;
                        break;
                    }
                    status = CollectionStatus.PARTIAL;
                    continue;
                }
                step = stepRepository.save(step);
            }
            finishStep(step, status);
            log.info("수집 경로 종료 runId={} endpoint={} status={} pages={} received={} created={} changed={} "
                            + "unchanged={} quarantined={} unlinked={}", runId, endpoint, status, step.getPageCount(),
                    step.getReceivedCount(), step.getCreatedCount(), step.getChangedCount(),
                    step.getUnchangedCount(), step.getQuarantinedCount(), step.getUnlinkedCount());
            return new StepResult(status, failure);
        } catch (RunAborted e) {
            finishStep(step, page == 1 ? CollectionStatus.FAILED : CollectionStatus.PARTIAL);
            throw e;
        } catch (RuntimeException e) {
            step.fail(now());
            throw e;
        } finally {
            stepRepository.save(step);
        }
    }

    private void finishStep(CollectionRunStep step, CollectionStatus status) {
        switch (status) {
            case SUCCEEDED -> step.succeed(now());
            case PARTIAL -> step.partial(now());
            default -> step.fail(now());
        }
    }

    /**
     * 실패한 쪽을 기록한다. 기록조차 저장하지 못하면 DB를 쓸 수 없는 상태로 보고 실행을 중단한다.
     *
     * @param cause 로그에 스택을 남길 원인. API 실패는 메시지로 충분해 null을 넘긴다
     */
    private PageFailure recordFailure(Long runId, SourceEndpoint endpoint, int page, CollectionErrorType type,
                                      int attempts, String message, Throwable cause) {
        log.warn("수집 쪽 실패 runId={} endpoint={} page={} errorType={} attempts={} message={}", runId, endpoint,
                page, type, attempts, message, cause);
        try {
            failureRepository.save(CollectionFailure.of(runId, endpoint, page, type, attempts, message, now()));
        } catch (DataAccessException | TransactionException | PersistenceException e) {
            log.error("수집 실패 기록 저장 실패 runId={} endpoint={} page={} errorType={}", runId, endpoint, page,
                    CollectionErrorType.DB_ERROR, e);
            throw new RunAborted(endpoint, page, CollectionErrorType.DB_ERROR,
                    "실패 기록 저장 실패: " + describeDbError(e));
        }
        return new PageFailure(type, message);
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

    private record StepResult(CollectionStatus status, PageFailure failure) {
    }

    private record PageFailure(CollectionErrorType type, String message) {
    }

    /**
     * 실행을 더 진행하지 않는 실패(인증 실패, 호출 제한, 예산 초과, 실패 기록 저장 불가).
     */
    private static final class RunAborted extends RuntimeException {

        private final SourceEndpoint endpoint;
        private final int page;
        private final CollectionErrorType type;

        private RunAborted(SourceEndpoint endpoint, int page, CollectionErrorType type, String message) {
            super(message, null, false, false);
            this.endpoint = endpoint;
            this.page = page;
            this.type = type;
        }
    }
}
