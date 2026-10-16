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
 * 비밀번호 재설정 메일 한 번(결정 0019). 같은 계정의 가장 최근 행만 쓸 수 있고, 한 번 쓰면 끝난다.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** 원문의 SHA-256({@link OpaqueTokens#hash}). 원문은 저장하지 않는다. */
    @Column(columnDefinition = "char(64)")
    private String tokenHash;

    /** 발송 시각. 발송 제한도 이 값으로 센다. */
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    /** 비밀번호를 재설정한 시각 */
    private LocalDateTime usedAt;

    public static PasswordResetToken issue(Long userId, String tokenHash, LocalDateTime now, Duration ttl) {
        PasswordResetToken token = new PasswordResetToken();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = now.plus(ttl);
        return token;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    /** 만료 시각이 된 순간부터 쓸 수 없다. */
    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
