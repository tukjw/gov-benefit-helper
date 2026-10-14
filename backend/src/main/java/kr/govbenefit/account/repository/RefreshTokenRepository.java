package kr.govbenefit.account.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.govbenefit.account.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 아직 교체·폐기되지 않은 토큰만 교체 표시한다. 같은 토큰의 동시 재발급 중 하나만 1을 받는다(결정 0017).
     *
     * @return 바꾼 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.rotatedAt = :now"
            + " where t.id = :id and t.rotatedAt is null and t.revokedAt is null")
    int markRotated(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 같은 로그인에서 이어진 토큰 중 폐기되지 않은 것을 모두 폐기한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") LocalDateTime now);
}
