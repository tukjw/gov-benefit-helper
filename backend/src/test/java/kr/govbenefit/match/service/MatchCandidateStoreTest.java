package kr.govbenefit.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.collection.domain.CollectionRunFinished;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.region.domain.AgencyRegion;
import org.junit.jupiter.api.Test;

/**
 * 판정 입력의 생성과 교체(결정 0038, REQ-QUALITY-02). DB 대신 멈춰 둘 수 있는 가짜 loader로 순서를 재현한다.
 */
class MatchCandidateStoreTest {

    private static final List<MatchCandidate> BEFORE_COLLECTION = List.of(candidate("수집 전"));
    private static final List<MatchCandidate> AFTER_COLLECTION = List.of(candidate("수집 후"));
    private static final CollectionRunFinished FINISHED = new CollectionRunFinished(1L, CollectionStatus.SUCCEEDED);

    private final MatchCandidateLoader loader = mock(MatchCandidateLoader.class);
    private final MatchCandidateStore store = new MatchCandidateStore(loader);

    @Test
    void 수집_전에_시작한_생성이_교체보다_늦게_끝나도_교체한_것이_남는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(loader.load()).willAnswer(invocation -> {
            loading.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return BEFORE_COLLECTION;
        }).willReturn(AFTER_COLLECTION);

        // 비어 있을 때 들어온 요청이 수집 전 데이터를 읽는 도중에 수집이 끝난다.
        CompletableFuture<List<MatchCandidate>> request = CompletableFuture.supplyAsync(store::get);
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Thread collection = new Thread(() -> store.refresh(FINISHED));
        collection.start();
        awaitBlocked(collection);
        release.countDown();

        assertThat(request.get(5, TimeUnit.SECONDS)).isSameAs(BEFORE_COLLECTION);
        collection.join(5_000);
        assertThat(store.get()).isSameAs(AFTER_COLLECTION);
        verify(loader, times(2)).load();
    }

    @Test
    void 비어_있을_때_동시에_들어온_요청은_한_번_만든_것을_함께_쓴다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        given(loader.load()).willAnswer(invocation -> {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return BEFORE_COLLECTION;
        });

        List<CompletableFuture<List<MatchCandidate>>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(CompletableFuture.supplyAsync(store::get));
        }
        release.countDown();

        for (CompletableFuture<List<MatchCandidate>> request : requests) {
            assertThat(request.get(5, TimeUnit.SECONDS)).isSameAs(BEFORE_COLLECTION);
        }
        verify(loader, times(1)).load();
    }

    @Test
    void 교체에_실패하면_비워_두고_다음_요청이_다시_만든다() {
        given(loader.load()).willReturn(BEFORE_COLLECTION).willThrow(new IllegalStateException("DB 오류"))
                .willReturn(AFTER_COLLECTION);
        store.get();

        // 실패는 수집 스레드로 그대로 올라간다.
        assertThatThrownBy(() -> store.refresh(FINISHED)).isInstanceOf(IllegalStateException.class);

        assertThat(store.get()).isSameAs(AFTER_COLLECTION);
        verify(loader, times(3)).load();
    }

    /** 교체가 잠금을 기다리는 상태가 될 때까지 기다린다. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static MatchCandidate candidate(String name) {
        return new MatchCandidate(new BenefitSummary("S01", name, null, null, null, null, null, null, null, null),
                "개인", AgencyRegion.NONE, null);
    }
}
