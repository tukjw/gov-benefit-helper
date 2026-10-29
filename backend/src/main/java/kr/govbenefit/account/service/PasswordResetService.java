package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import kr.govbenefit.account.config.AccountMailProperties;
import kr.govbenefit.account.config.PasswordResetProperties;
import kr.govbenefit.account.domain.Emails;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.domain.PasswordResetToken;
import kr.govbenefit.account.domain.SendRateLimit;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.mail.PasswordResetIssued;
import kr.govbenefit.account.repository.PasswordResetTokenRepository;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정(REQ-ACCOUNT-01, DEC-AUTH, 결정 0019). 메일의 일회용 링크로 비밀번호를 바꾸고 그 계정의 리프레시 토큰을
 * 모두 폐기한다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetTokenRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordResetProperties properties;
    private final AccountMailProperties mailProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * 가입된 이메일이면 재설정 링크를 보낸다. 가입되지 않았거나 발송 제한에 걸렸으면 아무것도 하지 않는다. 어느 경우든 호출한
     * 쪽에는 같은 결과다. 이전에 보낸 링크는 이때부터 쓸 수 없다.
     */
    @Transactional
    public void request(String email) {
        Optional<UserAccount> account = userAccountRepository.findByEmail(Emails.normalize(email));
        if (account.isEmpty()) {
            return;
        }
        Long userId = account.get().getId();
        LocalDateTime now = now();
        SendRateLimit limit = mailProperties.toSendRateLimit();
        LocalDateTime windowStart = limit.windowStart(now);
        Optional<Duration> retryAfter = limit.retryAfter(now,
                repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId).map(PasswordResetToken::getCreatedAt),
                repository.countByUserIdAndCreatedAtAfter(userId, windowStart),
                repository.findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(userId, windowStart)
                        .map(PasswordResetToken::getCreatedAt));
        if (retryAfter.isPresent()) {
            return;
        }
        String token = OpaqueTokens.generate();
        PasswordResetToken saved = repository.save(
                PasswordResetToken.issue(userId, OpaqueTokens.hash(token), now, properties.tokenTtl()));
        events.publishEvent(new PasswordResetIssued(saved.getId(), account.get().getEmail(), properties.linkFor(token),
                properties.tokenTtl()));
    }

    /**
     * 토큰으로 비밀번호를 바꾸고 그 계정의 모든 로그인에서 받은 리프레시 토큰을 폐기한다. 이미 발급된 액세스 토큰은 만료까지
     * 유효하다(DEC-AUTH의 한계). 메일 소유를 확인했으므로 그 이메일의 로그인 시도 제한을 푼다(결정 0035).
     *
     * @param newPassword 가입과 같은 규칙을 통과한 비밀번호
     * @throws BusinessException {@code INVALID_RESET_TOKEN}. 형식이 틀렸거나, 없거나, 썼거나, 만료됐거나, 더 나중에
     *                           보낸 링크가 있다.
     */
    @Transactional
    public void reset(String token, String newPassword) {
        if (!OpaqueTokens.isWellFormed(token)) {
            throw invalidToken();
        }
        LocalDateTime now = now();
        PasswordResetToken resetToken = repository.findByTokenHash(OpaqueTokens.hash(token))
                .orElseThrow(PasswordResetService::invalidToken);
        if (resetToken.isUsed() || resetToken.isExpiredAt(now)
                || repository.existsByUserIdAndIdGreaterThan(resetToken.getUserId(), resetToken.getId())) {
            throw invalidToken();
        }
        if (repository.markUsed(resetToken.getId(), now) == 0) {
            throw invalidToken();
        }
        UserAccount account = userAccountRepository.findById(resetToken.getUserId())
                .orElseThrow(PasswordResetService::invalidToken);
        account.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenService.revokeAllOf(account.getId());
        // 트랜잭션이 뒤에서 롤백되면 기록만 지워진다. 유효한 재설정 링크가 있어야 하고 얻는 것은 시도 몇 번이라 그대로 둔다.
        loginAttemptLimiter.clearEmail(account.getEmail());
    }

    private static BusinessException invalidToken() {
        return new BusinessException(ErrorCode.INVALID_RESET_TOKEN);
    }

    /** DB 컬럼(DATETIME(6))에 맞춰 마이크로초로 자른다. MySQL은 넘치는 자리를 반올림한다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
