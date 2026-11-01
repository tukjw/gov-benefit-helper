package kr.govbenefit.match.service;

import java.util.List;
import kr.govbenefit.collection.domain.CollectionRunFinished;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 조건 비교 목록의 판정 입력(결정 0038). 혜택과 지원조건은 수집으로만 바뀌므로, 수집 실행이 끝날 때마다 새로 만들어 바꾼다.
 * 그래서 목록은 마지막으로 끝난 수집 기준이고, 수집이 도는 동안에는 직전 수집 기준이다.
 * 만들기와 바꾸기는 한 잠금 안에서 한다. 그 전에 시작한 만들기가 늦게 끝나도 수집 뒤의 교체가 그 뒤에 덮는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchCandidateStore {

    private final MatchCandidateLoader loader;
    private final Object lock = new Object();
    /** 기동 직후와 교체에 실패한 뒤에는 null이다. */
    private volatile List<MatchCandidate> candidates;

    /**
     * 비어 있으면 DB에서 만든다. 동시에 들어온 요청은 한 번 만드는 것을 기다린다.
     */
    public List<MatchCandidate> get() {
        List<MatchCandidate> current = candidates;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (candidates == null) {
                candidates = loader.load();
            }
            return candidates;
        }
    }

    /**
     * 수집 실행이 끝나면 새로 만들어 바꾼다. 만드는 동안 요청은 이전 것을 쓴다. 실패하면 비워 두고 다음 요청이 다시 만든다.
     */
    @EventListener
    public void refresh(CollectionRunFinished event) {
        synchronized (lock) {
            long start = System.nanoTime();
            try {
                candidates = loader.load();
            } catch (RuntimeException e) {
                candidates = null;
                throw e;
            }
            log.info("조건 비교 목록 교체 runId={} candidates={} elapsedMs={}", event.runId(), candidates.size(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** 테스트가 메서드마다 비운다(결정 0033). */
    public void clear() {
        synchronized (lock) {
            candidates = null;
        }
    }
}
