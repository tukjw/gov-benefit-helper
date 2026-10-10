package kr.govbenefit.account.repository;

import java.util.Optional;
import kr.govbenefit.account.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** 이메일 unique 제약 이름. 동시 가입의 제약 위반을 구분할 때 쓴다. */
    String EMAIL_UNIQUE = "uk_user_account_email";

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
