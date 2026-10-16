package kr.govbenefit.account.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.govbenefit.account.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 같은 계정에 더 나중에 보낸 토큰이 있으면 이 토큰은 무효다(결정 0019). */
    boolean existsByUserIdAndIdGreaterThan(Long userId, Long id);

    Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);

    Optional<PasswordResetToken> findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(Long userId,
                                                                                           LocalDateTime after);

    /**
     * 쓰지 않은 토큰만 사용 표시한다. 같은 토큰의 동시 재설정 중 하나만 1을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
