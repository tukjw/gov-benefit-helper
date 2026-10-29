package kr.govbenefit.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가입 전 이메일 인증번호 발송 한 번(결정 0019). 같은 이메일의 가장 최근 행만 확인할 수 있다.
 * 번호를 맞히면 같은 행에 가입 티켓의 해시를 남기고, 가입하면 티켓을 쓴 시각을 남긴다.
 */
@Entity
@Table(name = "email_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link Emails#normalize}로 바꾼 값 */
    private String email;

    /** 인증번호의 SHA-256({@link VerificationCodes#hash}) */
    @Column(columnDefinition = "char(64)")
    private String codeHash;

    /** 인증번호를 비교한 횟수. 비교하기 전에 늘린다. */
    private int attempts;

    /** 발송 시각. 발송 제한도 이 값으로 센다. */
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    /** 인증번호를 맞힌 시각. 이후 이 번호는 다시 쓸 수 없다. */
    private LocalDateTime verifiedAt;

    /** 가입 티켓의 SHA-256({@link OpaqueTokens#hash}) */
    @Column(columnDefinition = "char(64)")
    private String ticketHash;

    private LocalDateTime ticketExpiresAt;

    /** 가입에 티켓을 쓴 시각 */
    private LocalDateTime ticketUsedAt;

    public static EmailVerification issue(String email, String codeHash, LocalDateTime now, Duration ttl) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.codeHash = codeHash;
        verification.createdAt = now;
        verification.expiresAt = now.plus(ttl);
        return verification;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** 만료 시각이 된 순간부터 쓸 수 없다. */
    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * 확인한 이메일과 같은 이메일로, 아직 쓰지 않았고, 만료 시각 전일 때만 가입에 쓸 수 있다.
     *
     * @param email {@link Emails#normalize}로 바꾼 값
     */
    public boolean isTicketUsableAt(LocalDateTime now, String email) {
        return ticketHash != null
                && ticketUsedAt == null
                && now.isBefore(ticketExpiresAt)
                && this.email.equals(email);
    }
}
