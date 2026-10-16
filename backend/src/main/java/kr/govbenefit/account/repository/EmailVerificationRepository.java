package kr.govbenefit.account.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kr.govbenefit.account.domain.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 같은 이메일의 가장 최근 발송. 확인할 수 있는 유일한 행이다(결정 0019). */
    Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDescIdDesc(String email);

    Optional<EmailVerification> findByTicketHash(String ticketHash);

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime after);

    Optional<EmailVerification> findFirstByEmailAndCreatedAtAfterOrderByCreatedAtAscIdAsc(String email,
                                                                                         LocalDateTime after);

    /**
     * 인증번호를 비교하기 전에 시도 한 번을 예약한다. 행을 잠그고 최신 값을 보므로 동시 요청이 몇 개든 1을 받는 요청은
     * {@code maxAttempts}개까지다(결정 0019).
     *
     * @return 바꾼 행 수(0 또는 1). 0이면 횟수를 다 썼거나 이미 확인된 번호다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailVerification v set v.attempts = v.attempts + 1"
            + " where v.id = :id and v.attempts < :maxAttempts and v.verifiedAt is null")
    int reserveAttempt(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    /**
     * 확인되지 않은 번호만 확인 표시하고 가입 티켓을 남긴다. 같은 번호의 동시 확인 중 하나만 1을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailVerification v set v.verifiedAt = :now, v.ticketHash = :ticketHash,"
            + " v.ticketExpiresAt = :ticketExpiresAt where v.id = :id and v.verifiedAt is null")
    int markVerified(@Param("id") Long id, @Param("ticketHash") String ticketHash,
                     @Param("ticketExpiresAt") LocalDateTime ticketExpiresAt, @Param("now") LocalDateTime now);

    /**
     * 쓰지 않은 가입 티켓만 사용 표시한다. 같은 티켓의 동시 가입 중 하나만 1을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailVerification v set v.ticketUsedAt = :now where v.id = :id and v.ticketUsedAt is null")
    int useTicket(@Param("id") Long id, @Param("now") LocalDateTime now);
}
