package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import kr.govbenefit.account.domain.Emails;
import kr.govbenefit.account.domain.PasswordRule;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.dto.LoginRequest;
import kr.govbenefit.account.dto.LoginResponse;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.common.security.AccessTokenIssuer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입·로그인·내 정보(REQ-ACCOUNT-01, DEC-AUTH).
 */
@Service
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final Clock clock;
    /** 가입되지 않은 이메일로 로그인할 때도 해시를 비교해 응답 시간으로 가입 여부를 알 수 없게 한다(결정 0013). */
    private final String dummyPasswordHash;

    public AccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder,
                          AccessTokenIssuer accessTokenIssuer, Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 같은 이메일의 동시 가입은 선조회를 함께 통과할 수 있다. 그때는 unique 제약 위반을 같은 409로 바꾼다.
     */
    @Transactional
    public AccountResponse signUp(SignUpRequest request) {
        String email = Emails.normalize(request.email());
        if (userAccountRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
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
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<UserAccount> account = userAccountRepository.findByEmail(Emails.normalize(request.email()));
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
        return LoginResponse.from(accessTokenIssuer.issue(account.get().getId()));
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
