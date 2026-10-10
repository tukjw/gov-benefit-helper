package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import kr.govbenefit.account.domain.Emails;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.ApiException;
import kr.govbenefit.common.api.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입(REQ-ACCOUNT-01, DEC-AUTH).
 */
@Service
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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

    static boolean isEmailUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(UserAccountRepository.EMAIL_UNIQUE)) {
                return true;
            }
        }
        return false;
    }

    private static ApiException duplicateEmail() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_EMAIL);
    }
}
