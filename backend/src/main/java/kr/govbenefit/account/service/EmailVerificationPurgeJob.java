package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import kr.govbenefit.account.config.AccountMailProperties;
import kr.govbenefit.account.repository.EmailVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증 기록 파기(REQ-ACCOUNT-01, DEC-AUTH, 결정 0041). 하루 한 번 발송 제한 창({@code auth.mail.window}) 밖으로 나간
 * 기록을 가입 여부와 관계없이 지운다. 창 밖의 기록은 인증번호·가입 티켓·발송 제한 어디에도 쓰이지 않는다.
 *
 * <p>단일 인스턴스를 전제한다. 실패하면 남은 기록은 다음 실행에서 함께 지워진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationPurgeJob {

    private final EmailVerificationRepository repository;
    private final AccountMailProperties mailProperties;
    private final Clock clock;

    /**
     * @return 지운 기록 수
     */
    @Scheduled(cron = "${auth.email-verification.purge-cron}", zone = "Asia/Seoul")
    @Transactional
    public int purge() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        int deleted = repository.deleteSentAtOrBefore(mailProperties.toSendRateLimit().windowStart(now));
        log.info("이메일 인증 기록을 파기했습니다. deleted={}", deleted);
        return deleted;
    }
}
