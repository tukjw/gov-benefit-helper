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
 * 리프레시 토큰 한 개(결정 0017). 재발급하면 이 행에 {@code rotatedAt}을 남기고 같은 {@code familyId}로 새 행을 만든다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** 한 번의 로그인에서 교체로 이어진 토큰이 같은 값을 갖는다. */
    @Column(columnDefinition = "char(36)")
    private String familyId;

    /** 원문의 SHA-256({@link OpaqueTokens#hash}). 원문은 저장하지 않는다. */
    @Column(columnDefinition = "char(64)")
    private String tokenHash;

    private LocalDateTime issuedAt;

    private LocalDateTime expiresAt;

    /** 다음 토큰으로 교체된 시각 */
    private LocalDateTime rotatedAt;

    /** 로그아웃이나 재사용 감지로 폐기된 시각 */
    private LocalDateTime revokedAt;

    public static RefreshToken issue(Long userId, String familyId, String tokenHash, LocalDateTime now, Duration ttl) {
        RefreshToken token = new RefreshToken();
        token.userId = userId;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.issuedAt = now;
        token.expiresAt = now.plus(ttl);
        return token;
    }

    public boolean isRotated() {
        return rotatedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 만료 시각이 된 순간부터 쓸 수 없다. */
    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
