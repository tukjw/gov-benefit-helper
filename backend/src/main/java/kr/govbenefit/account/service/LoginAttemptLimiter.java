package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import kr.govbenefit.account.config.LoginAttemptProperties;
import kr.govbenefit.account.domain.ClientIps;
import kr.govbenefit.common.api.RateLimitedException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 로그인·탈퇴의 비밀번호 확인 시도 제한(REQ-ACCOUNT-01, DEC-AUTH, 결정 0035). 최근 창 안의 실패 시각을 (이메일+IP)별,
 * IP별로 기록하고 어느 쪽이든 한도에 차 있으면 거절한다. 계정은 잠그지 않으므로 다른 IP의 계정 주인은 영향을 받지 않는다.
 *
 * <p>시도는 비밀번호를 비교하기 전에 실패로 먼저 기록하고({@link #reserve}), 맞으면 되돌린다({@link #succeeded}).
 * 비교한 뒤에 기록하면 비교(BCrypt)하는 동안 들어온 병렬 요청이 모두 확인을 통과한다. 두 기준을 함께 보고 기록해야 하므로
 * 키 단위 잠금이 아니라 전체를 동기화한다. 메모리 연산뿐이라 비용이 작다.
 *
 * <p>기록은 앱 메모리에 둔다. 재시작하면 사라지고, 앱을 여러 개 띄우면 인스턴스마다 따로 센다.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    private final LoginAttemptProperties properties;
    private final Clock clock;

    /** 이메일 → IP 키 → 창 안의 실패 시각(오래된 순). 이메일로 묶어 재설정 때 한 번에 지운다. */
    private final Map<String, Map<String, Deque<Instant>>> byEmail = new HashMap<>();
    /** IP 키 → 창 안의 실패 시각(오래된 순). */
    private final Map<String, Deque<Instant>> byIp = new HashMap<>();

    /**
     * 두 기준 모두 한도 미만이면 이번 시도를 실패로 기록한다. 가입 여부와 관계없이 같은 기준이다.
     *
     * @param email      정규화한 이메일
     * @param remoteAddr 요청자 주소
     * @throws RateLimitedException 어느 기준이든 한도에 차 있다. 대기 시간은 두 기준 중 긴 쪽이다. 이때는 기록하지 않는다.
     */
    public synchronized Attempt reserve(String email, String remoteAddr) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        String ipKey = ClientIps.limitKey(remoteAddr);
        Deque<Instant> emailAndIpFailures = byEmail.computeIfAbsent(email, key -> new HashMap<>())
                .computeIfAbsent(ipKey, key -> new ArrayDeque<>());
        Deque<Instant> ipFailures = byIp.computeIfAbsent(ipKey, key -> new ArrayDeque<>());
        prune(emailAndIpFailures, windowStart);
        prune(ipFailures, windowStart);
        Duration wait = max(waitFor(emailAndIpFailures, properties.maxFailuresPerEmailAndIp(), now),
                waitFor(ipFailures, properties.maxFailuresPerIp(), now));
        if (wait.isPositive()) {
            throw new RateLimitedException(wait);
        }
        emailAndIpFailures.addLast(now);
        ipFailures.addLast(now);
        return new Attempt(email, ipKey, now);
    }

    /**
     * 비밀번호가 맞았다. 그 (이메일+IP) 기록은 모두 지우고, IP 기록에서는 이번 시도만 지운다. IP 기록을 모두 지우면
     * 공격자가 자기 계정으로 로그인해 IP 한도를 초기화할 수 있다.
     */
    public synchronized void succeeded(Attempt attempt) {
        Map<String, Deque<Instant>> ips = byEmail.get(attempt.email());
        if (ips != null) {
            ips.remove(attempt.ipKey());
            if (ips.isEmpty()) {
                byEmail.remove(attempt.email());
            }
        }
        Deque<Instant> ipFailures = byIp.get(attempt.ipKey());
        if (ipFailures != null) {
            ipFailures.removeFirstOccurrence(attempt.at());
            if (ipFailures.isEmpty()) {
                byIp.remove(attempt.ipKey());
            }
        }
    }

    /**
     * 비밀번호를 재설정했다. 메일 소유를 확인했으므로 그 이메일의 (이메일+IP) 기록을 모두 지운다. IP 기록은 그대로다.
     */
    public synchronized void clearEmail(String email) {
        byEmail.remove(email);
    }

    /**
     * 한동안 시도가 없는 키는 {@link #reserve}에서 정리되지 않으므로 주기적으로 창 밖의 기록과 빈 키를 지운다.
     */
    @Scheduled(fixedDelayString = "${auth.login-attempt.window}", initialDelayString = "${auth.login-attempt.window}")
    public synchronized void evictExpired() {
        Instant windowStart = clock.instant().minus(properties.window());
        Iterator<Map<String, Deque<Instant>>> emails = byEmail.values().iterator();
        while (emails.hasNext()) {
            Map<String, Deque<Instant>> ips = emails.next();
            pruneAll(ips, windowStart);
            if (ips.isEmpty()) {
                emails.remove();
            }
        }
        pruneAll(byIp, windowStart);
    }

    /** 테스트가 메서드마다 기록을 비운다(결정 0033). */
    public synchronized void clear() {
        byEmail.clear();
        byIp.clear();
    }

    /** 남은 기록이 없으면 참이다. */
    public synchronized boolean isEmpty() {
        return byEmail.isEmpty() && byIp.isEmpty();
    }

    private static void pruneAll(Map<String, Deque<Instant>> failures, Instant windowStart) {
        Iterator<Deque<Instant>> iterator = failures.values().iterator();
        while (iterator.hasNext()) {
            Deque<Instant> deque = iterator.next();
            prune(deque, windowStart);
            if (deque.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void prune(Deque<Instant> failures, Instant windowStart) {
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(windowStart)) {
            failures.removeFirst();
        }
    }

    private Duration waitFor(Deque<Instant> failures, int max, Instant now) {
        if (failures.size() < max) {
            return Duration.ZERO;
        }
        return Duration.between(now, failures.peekFirst().plus(properties.window()));
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * 기록한 시도. 비밀번호가 맞으면 {@link #succeeded}로 되돌린다.
     */
    public record Attempt(String email, String ipKey, Instant at) {
    }
}
