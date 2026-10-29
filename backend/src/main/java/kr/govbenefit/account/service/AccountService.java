package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import kr.govbenefit.account.domain.EmailVerification;
import kr.govbenefit.account.domain.Emails;
import kr.govbenefit.account.domain.PasswordRule;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.dto.LoginRequest;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.common.security.AccessTokenIssuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입·로그인·로그인 유지·로그아웃·내 정보·탈퇴(REQ-ACCOUNT-01, DEC-AUTH). 가입은 이메일 인증을 마친 뒤에만 한다(결정 0019).
 */
@Slf4j
@Service
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final Clock clock;
    /** 가입되지 않은 이메일로 로그인할 때도 해시를 비교해 응답 시간으로 가입 여부를 알 수 없게 한다(결정 0013). */
    private final String dummyPasswordHash;

    public AccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder,
                          AccessTokenIssuer accessTokenIssuer, RefreshTokenService refreshTokenService,
                          EmailVerificationService emailVerificationService, LoginAttemptLimiter loginAttemptLimiter,
                          Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.emailVerificationService = emailVerificationService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 가입 티켓을 먼저 검사하고 그다음 이메일 중복을 본다. 순서가 반대면 티켓 없이도 409로 가입 여부를 알아낼 수 있다.
     * 같은 이메일의 동시 가입은 선조회를 함께 통과할 수 있다. 그때는 unique 제약 위반을 같은 409로 바꾼다. 409는 롤백되므로
     * 티켓 사용도 되돌려진다.
     */
    @Transactional
    public AccountResponse signUp(SignUpRequest request) {
        String email = Emails.normalize(request.email());
        EmailVerification verification = emailVerificationService.checkTicket(email, request.verificationToken());
        if (userAccountRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
        emailVerificationService.useTicket(verification);
        UserAccount account = UserAccount.create(email, passwordEncoder.encode(request.password()),
                LocalDateTime.now(clock));
        try {
            userAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            if (isEmailUniqueViolation(ex)) {
                throw duplicateEmail();
            }
            throw ex;
        }
        return AccountResponse.from(account);
    }

    /**
     * 비밀번호가 틀린 경우와 가입되지 않은 이메일은 같은 실패다. 72바이트를 넘는 비밀번호는 비교하지 않고 실패다.
     * BCrypt는 앞 72바이트만 비교하므로, 비교하면 72바이트 비밀번호 뒤에 아무 글자를 붙여도 맞다고 한다(결정 0013).
     * 성공하면 새 로그인의 리프레시 토큰을 함께 발급한다.
     *
     * <p>계정을 조회하기 전에 시도 제한을 확인하므로 제한 응답으로 가입 여부를 알 수 없다(결정 0035).
     *
     * @param remoteAddr 요청자 주소. 시도 제한에 쓴다.
     */
    @Transactional
    public AuthTokens login(LoginRequest request, String remoteAddr) {
        String email = Emails.normalize(request.email());
        LoginAttemptLimiter.Attempt attempt = loginAttemptLimiter.reserve(email, remoteAddr);
        Optional<UserAccount> account = userAccountRepository.findByEmail(email);
        String password = request.password();
        if (!PasswordRule.isWithinMaxBytes(password)) {
            throw invalidCredentials();
        }
        if (account.isEmpty()) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, account.get().getPasswordHash())) {
            throw invalidCredentials();
        }
        loginAttemptLimiter.succeeded(attempt);
        Long userId = account.get().getId();
        return new AuthTokens(accessTokenIssuer.issue(userId), refreshTokenService.issueNewFamily(userId));
    }

    /**
     * 리프레시 토큰을 교체하고 새 액세스 토큰을 발급한다. 쓸 수 없는 토큰이면 빈 결과다({@link RefreshTokenService#rotate}).
     */
    @Transactional
    public Optional<AuthTokens> refresh(String refreshToken) {
        return refreshTokenService.rotate(refreshToken)
                .map(rotation -> new AuthTokens(accessTokenIssuer.issue(rotation.userId()), rotation.refreshToken()));
    }

    /**
     * 리프레시 토큰이 속한 로그인의 토큰을 모두 폐기한다. 이미 발급된 액세스 토큰은 만료까지 유효하다(DEC-AUTH의 한계).
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revokeFamilyOf(refreshToken);
    }

    /**
     * 토큰은 유효하지만 계정이 없으면(탈퇴 등) 인증되지 않은 요청으로 본다.
     */
    @Transactional(readOnly = true)
    public AccountResponse me(Long userId) {
        return userAccountRepository.findById(userId)
                .map(AccountResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    /**
     * 비밀번호를 다시 확인하고 계정을 지운다. 프로필·리프레시 토큰·재설정 토큰은 FK {@code ON DELETE CASCADE}로 함께
     * 지워진다. 계정 행을 잠가 같은 사용자의 프로필 저장·탈퇴와 순서를 정한다. 이미 없는 계정이면(탈퇴 뒤 남은 토큰)
     * 인증되지 않은 요청이다. 비밀번호 비교는 로그인과 같다(결정 0013). 실패는 그 계정의 이메일과 요청자 주소로 로그인과 같은
     * 시도 제한에 센다(결정 0035).
     *
     * <p>한계: 탈퇴가 커밋되는 순간 같은 사용자의 로그인·재발급이 리프레시 토큰을 저장하면 FK 위반으로 실패한다.
     */
    @Transactional
    public void withdraw(Long userId, String password, String remoteAddr) {
        UserAccount account = userAccountRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        LoginAttemptLimiter.Attempt attempt = loginAttemptLimiter.reserve(account.getEmail(), remoteAddr);
        if (!PasswordRule.isWithinMaxBytes(password) || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        loginAttemptLimiter.succeeded(attempt);
        userAccountRepository.delete(account);
        log.info("계정을 탈퇴했습니다. userId={}", userId);
    }

    static boolean isEmailUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(UserAccountRepository.EMAIL_UNIQUE)) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException duplicateEmail() {
        return new BusinessException(ErrorCode.DUPLICATE_EMAIL);
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
}
