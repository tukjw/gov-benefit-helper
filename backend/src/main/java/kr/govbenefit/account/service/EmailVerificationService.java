package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import kr.govbenefit.account.config.AccountMailProperties;
import kr.govbenefit.account.config.EmailVerificationProperties;
import kr.govbenefit.account.domain.EmailVerification;
import kr.govbenefit.account.domain.Emails;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.domain.SendRateLimit;
import kr.govbenefit.account.domain.VerificationCodes;
import kr.govbenefit.account.mail.SignupCodeIssued;
import kr.govbenefit.account.repository.EmailVerificationRepository;
import kr.govbenefit.account.service.ConfirmOutcome.Confirmed;
import kr.govbenefit.account.service.ConfirmOutcome.Rejected;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.common.api.RateLimitedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 전 이메일 인증(REQ-ACCOUNT-01, DEC-AUTH, 결정 0019). 인증번호를 맞히면 가입 티켓을 주고, 가입은 그 티켓으로만 한다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationRepository repository;
    private final EmailVerificationProperties properties;
    private final AccountMailProperties mailProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * 인증번호를 새로 발급해 메일로 보낸다. 이전 번호는 이때부터 쓸 수 없다. 이미 가입된 이메일인지는 보지 않는다.
     *
     * @throws RateLimitedException 같은 이메일에 보낸 지 얼마 되지 않았거나 최근 발송 횟수를 다 썼다
     */
    @Transactional
    public void request(String email) {
        String normalized = Emails.normalize(email);
        LocalDateTime now = now();
        SendRateLimit limit = mailProperties.toSendRateLimit();
        LocalDateTime windowStart = limit.windowStart(now);
        Optional<Duration> retryAfter = limit.retryAfter(now,
                repository.findFirstByEmailOrderByCreatedAtDescIdDesc(normalized).map(EmailVerification::getCreatedAt),
                repository.countByEmailAndCreatedAtAfter(normalized, windowStart),
                repository.findFirstByEmailAndCreatedAtAfterOrderByCreatedAtAscIdAsc(normalized, windowStart)
                        .map(EmailVerification::getCreatedAt));
        if (retryAfter.isPresent()) {
            throw new RateLimitedException(retryAfter.get());
        }
        String code = VerificationCodes.generate();
        EmailVerification verification = repository.save(
                EmailVerification.issue(normalized, VerificationCodes.hash(code), now, properties.codeTtl()));
        events.publishEvent(new SignupCodeIssued(verification.getId(), normalized, code, properties.codeTtl()));
    }

    /**
     * 그 이메일의 가장 최근 인증번호와 비교한다. 다음 순서로 판정한다.
     * <ol>
     *     <li>보낸 적이 없거나, 이미 맞힌 번호거나, 만료됐으면 {@code VERIFICATION_CODE_EXPIRED}. 시도로 세지 않는다.</li>
     *     <li>시도 한 번을 예약한다. 횟수를 다 썼으면 {@code VERIFICATION_ATTEMPTS_EXCEEDED}.</li>
     *     <li>틀렸으면 {@code VERIFICATION_CODE_MISMATCH}.</li>
     *     <li>맞으면 가입 티켓을 발급한다. 동시에 다른 요청이 먼저 맞혔으면 {@code VERIFICATION_CODE_EXPIRED}.</li>
     * </ol>
     * 비교하기 전에 시도를 세므로 맞힌 시도도 한 번이다. 동시 요청 수십 개가 같은 횟수를 읽고 함께 비교하지 못한다.
     */
    @Transactional
    public ConfirmOutcome confirm(String email, String code) {
        LocalDateTime now = now();
        Optional<EmailVerification> found =
                repository.findFirstByEmailOrderByCreatedAtDescIdDesc(Emails.normalize(email));
        if (found.isEmpty() || found.get().isVerified() || found.get().isExpiredAt(now)) {
            return new Rejected(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        EmailVerification verification = found.get();
        // 0이면 횟수를 다 썼거나, 위에서 읽은 뒤 다른 요청이 맞혔다. REPEATABLE READ 스냅샷이라 다시 읽어도 구분할 수 없다.
        if (repository.reserveAttempt(verification.getId(), properties.maxAttempts()) == 0) {
            return new Rejected(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!VerificationCodes.matches(code, verification.getCodeHash())) {
            return new Rejected(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }
        String ticket = OpaqueTokens.generate();
        LocalDateTime ticketExpiresAt = now.plus(properties.ticketTtl());
        if (repository.markVerified(verification.getId(), OpaqueTokens.hash(ticket), ticketExpiresAt, now) == 0) {
            return new Rejected(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        return new Confirmed(ticket, ticketExpiresAt);
    }

    /**
     * 가입 티켓을 쓸 수 있는지 본다. 쓰지는 않는다({@link #useTicket}).
     *
     * @param email {@link Emails#normalize}로 바꾼 가입 이메일
     * @throws BusinessException {@code EMAIL_NOT_VERIFIED}. 형식이 틀렸거나, 없거나, 쓴 적이 있거나, 만료됐거나,
     *                           다른 이메일로 받은 티켓이다.
     */
    @Transactional
    public EmailVerification checkTicket(String email, String verificationToken) {
        if (!OpaqueTokens.isWellFormed(verificationToken)) {
            throw notVerified();
        }
        LocalDateTime now = now();
        return repository.findByTicketHash(OpaqueTokens.hash(verificationToken))
                .filter(verification -> verification.isTicketUsableAt(now, email))
                .orElseThrow(EmailVerificationService::notVerified);
    }

    /**
     * 가입 티켓을 사용 표시한다. 같은 티켓의 동시 가입은 여기서 하나만 통과한다.
     *
     * @throws BusinessException {@code EMAIL_NOT_VERIFIED}. 확인한 뒤 다른 요청이 먼저 썼다.
     */
    @Transactional
    public void useTicket(EmailVerification verification) {
        if (repository.useTicket(verification.getId(), now()) == 0) {
            throw notVerified();
        }
    }

    private static BusinessException notVerified() {
        return new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    /** DB 컬럼(DATETIME(6))에 맞춰 마이크로초로 자른다. MySQL은 넘치는 자리를 반올림한다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
