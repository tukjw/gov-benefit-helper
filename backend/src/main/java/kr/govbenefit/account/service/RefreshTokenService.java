package kr.govbenefit.account.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import kr.govbenefit.account.config.RefreshTokenProperties;
import kr.govbenefit.account.domain.OpaqueTokens;
import kr.govbenefit.account.domain.RefreshToken;
import kr.govbenefit.account.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰의 발급·교체·폐기(DEC-AUTH, 결정 0017).
 *
 * <p>거절은 예외가 아니라 빈 결과로 돌려준다. 재사용을 감지해 폐기한 것이 거절 응답과 함께 롤백되면 안 되기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    /**
     * 로그인 한 번에 해당하는 새 가족의 첫 토큰을 발급한다. DB에는 해시만 저장한다.
     *
     * @return 토큰 원문. 응답 쿠키로만 보낸다.
     */
    @Transactional
    public String issueNewFamily(Long userId) {
        return issue(userId, UUID.randomUUID().toString(), now());
    }

    /**
     * 토큰을 다음 토큰으로 교체한다. 다음 중 하나면 빈 결과다.
     * <ul>
     *     <li>형식이 틀렸거나 없는 토큰</li>
     *     <li>폐기된 토큰</li>
     *     <li>이미 교체된 토큰. 교체된 지 유예 시간이 지났으면 재사용으로 보고 같은 가족을 모두 폐기한다.</li>
     *     <li>만료된 토큰</li>
     *     <li>동시에 들어온 다른 요청이 먼저 교체한 토큰</li>
     * </ul>
     * 재사용 검사를 만료 검사보다 먼저 한다. 만료된 이전 토큰이 다시 나타난 것도 탈취 신호다.
     */
    @Transactional
    public Optional<Rotation> rotate(String rawToken) {
        if (!OpaqueTokens.isWellFormed(rawToken)) {
            return Optional.empty();
        }
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();
        LocalDateTime now = now();
        if (token.isRevoked()) {
            return Optional.empty();
        }
        if (token.isRotated()) {
            // 탭 여러 개의 동시 재발급이나 응답을 잃은 재시도는 교체 직후에 이전 토큰을 보낸다. 이때는 거절만 한다.
            if (now.isBefore(token.getRotatedAt().plus(properties.reuseGrace()))) {
                return Optional.empty();
            }
            int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            log.warn("교체된 리프레시 토큰이 다시 쓰여 같은 로그인의 토큰을 폐기했습니다. userId={}, familyId={}, 폐기={}건",
                    token.getUserId(), token.getFamilyId(), revoked);
            return Optional.empty();
        }
        if (token.isExpiredAt(now)) {
            return Optional.empty();
        }
        // 0이면 위에서 읽은 뒤 다른 요청이 방금 교체(또는 로그아웃)한 것이다. REPEATABLE READ 스냅샷이라 다시 읽어도
        // 바뀐 값이 보이지 않으므로 다시 읽지 않고, 방금 교체된 것이니 유예 안으로 보아 거절만 한다.
        if (refreshTokenRepository.markRotated(token.getId(), now) == 0) {
            return Optional.empty();
        }
        return Optional.of(new Rotation(token.getUserId(), issue(token.getUserId(), token.getFamilyId(), now)));
    }

    /**
     * 토큰이 속한 가족을 모두 폐기한다. 형식이 틀렸거나 없는 토큰이면 아무것도 하지 않는다. 교체된 이전 토큰이어도 폐기한다.
     */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        if (!OpaqueTokens.isWellFormed(rawToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now()));
    }

    private String issue(Long userId, String familyId, LocalDateTime now) {
        String value = OpaqueTokens.generate();
        refreshTokenRepository.save(
                RefreshToken.issue(userId, familyId, OpaqueTokens.hash(value), now, properties.ttl()));
        return value;
    }

    /** DB 컬럼(DATETIME(6))에 맞춰 마이크로초로 자른다. MySQL은 넘치는 자리를 반올림한다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * @param userId       토큰 주인
     * @param refreshToken 새 토큰 원문
     */
    public record Rotation(Long userId, String refreshToken) {
    }
}
