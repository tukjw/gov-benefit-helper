package kr.govbenefit.account.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.govbenefit.account.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** 이메일 unique 제약 이름. 동시 가입의 제약 위반을 구분할 때 쓴다. */
    String EMAIL_UNIQUE = "uk_user_account_email";

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 계정 행을 잠근다(SELECT ... FOR UPDATE). 같은 사용자의 프로필 저장을 직렬화해 첫 저장이 동시에 와도 행이 하나만 생기게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UserAccount a where a.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") Long id);
}
