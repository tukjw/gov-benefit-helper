package kr.govbenefit.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.account.mail.AccountMailSendException;
import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.notification.config.NewBenefitSendProperties;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.domain.NewBenefitDigestStatus;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import kr.govbenefit.notification.service.NewBenefitDigestSendWriter.NewBenefitDigestMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 새 혜택 알림 발송(REQ-NOTIFY-02, REQ-NOTIFY-03, 결정 0048). 발송 대기이고 보낼 차례가 된 묶음을 메일 한 통씩 SES로 보낸다.
 * 일시 실패는 정한 간격 뒤 다시 보내고, 묶은 날 24:00(KST)이 지나면 보내지 않는다. 상태는 DB에만 있어서, 앱이 다시 시작되면
 * 다음 실행이 남은 묶음을 이어서 보낸다.
 *
 * <p>단일 인스턴스와 스케줄러 스레드 하나를 전제한다. 묶기({@link NewBenefitDigestBundler})와 같은 스레드라 둘이 함께 돌지 않는다.
 * SES가 접수한 뒤 결과를 기록하기 전에 끝나면 그 묶음은 다시 보낸다.
 *
 * <p>앱이 종료를 시작하면(컨텍스트 종료 이벤트) 다음 묶음을 다루지 않고 실행을 끝낸다(결정 0049). 보내던 메일은 끊지 않고
 * 결과까지 기록하므로, 실행은 Spring의 종료 대기(30초) 안에 끝난다. 남은 묶음은 다음에 뜬 앱이 보낸다.
 */
@Slf4j
@Component
public class NewBenefitDigestSender {

    private static final int PAGE_SIZE = 100;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final NewBenefitDigestRepository digestRepository;
    private final NewBenefitDigestSendWriter writer;
    private final AccountMailSender mailSender;
    private final Clock clock;
    private final ApplicationContext applicationContext;
    private final String siteUrl;
    /** SES 호출 시작 사이의 최소 간격 */
    private final long intervalNanos;
    /** 다음 SES 호출을 시작해도 되는 시각({@link System#nanoTime()}) */
    private long nextSendNanos = System.nanoTime();
    /** 앱이 종료를 시작했다. 한번 켜지면 끄지 않는다. */
    private volatile boolean stopping;

    public NewBenefitDigestSender(NewBenefitDigestRepository digestRepository, NewBenefitDigestSendWriter writer,
                                  AccountMailSender mailSender, Clock clock, NewBenefitSendProperties properties,
                                  ApplicationContext applicationContext) {
        this.digestRepository = digestRepository;
        this.writer = writer;
        this.mailSender = mailSender;
        this.clock = clock;
        this.applicationContext = applicationContext;
        this.siteUrl = properties.siteUrl();
        this.intervalNanos = NANOS_PER_SECOND / properties.ratePerSecond();
    }

    /**
     * 실패해도 예외를 던지지 않는다. 남은 묶음은 다음 실행이 보낸다.
     */
    @Scheduled(cron = "${notification.new-benefit.send.cron}", zone = "Asia/Seoul")
    public void sendScheduled() {
        try {
            sendDue();
        } catch (RuntimeException e) {
            log.error("새 혜택 알림 발송 실패", e);
        }
    }

    /**
     * 종료의 가장 앞 단계라, 스케줄러가 실행 중인 발송을 기다리기 전에 받는다. 관리 포트의 자식 컨텍스트가 닫힐 때도 이 이벤트가
     * 전달되므로 이 앱의 컨텍스트인지 확인한다.
     */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (event.getApplicationContext() == applicationContext) {
            stopping = true;
        }
    }

    /**
     * 실행을 시작한 시각까지 보낼 차례가 된 묶음을 모두 보낸다. 한 실행에서 같은 묶음은 한 번만 다룬다. 실패해 다음 시도 시각이
     * 뒤로 간 묶음은 다음 실행이 다시 읽는다. 앱이 종료를 시작했으면 다음 묶음을 다루지 않고 끝낸다.
     */
    public void sendDue() {
        long start = System.nanoTime();
        LocalDateTime startedAt = LocalDateTime.now(clock);
        int accepted = 0;
        int retrying = 0;
        int failed = 0;
        int skipped = 0;
        Long afterId = 0L;
        boolean interrupted = false;
        while (!interrupted) {
            List<Long> ids = digestRepository.findDueIds(NewBenefitDigestStatus.PENDING, startedAt, afterId,
                    Limit.of(PAGE_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                if (stopping) {
                    interrupted = true;
                    break;
                }
                Optional<NewBenefitDigestMail> mail = writer.prepare(id, LocalDateTime.now(clock));
                if (mail.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (!awaitTurn()) {
                    interrupted = true;
                    break;
                }
                try {
                    mailSender.sendNewBenefitDigest(mail.get().email(), mail.get().benefits(), siteUrl);
                } catch (AccountMailSendException e) {
                    Optional<NewBenefitDigest> digest = writer.fail(id, e, LocalDateTime.now(clock));
                    boolean finalFailure = digest.map(d -> d.getStatus() != NewBenefitDigestStatus.PENDING)
                            .orElse(true);
                    // 수신 주소와 본문은 남기지 않는다. 예외 메시지에는 상태와 오류 코드만 있다(결정 0047).
                    log.warn("새 혜택 알림 발송 실패 digestId={} attempt={} retryable={} final={} error={}", id,
                            digest.map(NewBenefitDigest::getAttemptCount).orElse(null), e.isRetryable(),
                            finalFailure, e.getMessage());
                    if (finalFailure) {
                        failed++;
                    } else {
                        retrying++;
                    }
                    continue;
                }
                writer.accept(id, LocalDateTime.now(clock));
                accepted++;
            }
            afterId = ids.getLast();
        }
        if (accepted + retrying + failed + skipped > 0) {
            log.info("새 혜택 알림 발송 accepted={} retrying={} failed={} skipped={} interrupted={} elapsedMs={}",
                    accepted, retrying, failed, skipped, interrupted, (System.nanoTime() - start) / 1_000_000);
        }
    }

    /**
     * 앞 SES 호출을 시작한 뒤 최소 간격이 지날 때까지 기다린다. 발송 작업이 하나라 이 간격만으로 초당 건수를 넘지 않는다.
     * 업무 시계({@link Clock})가 아니라 실제 경과 시간으로 잰다.
     *
     * @return 기다리는 중에 interrupt되면(종료) {@code false}
     */
    private boolean awaitTurn() {
        long wait = nextSendNanos - System.nanoTime();
        if (wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        nextSendNanos = System.nanoTime() + intervalNanos;
        return true;
    }
}
