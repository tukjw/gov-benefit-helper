package kr.govbenefit.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import kr.govbenefit.common.api.ApiTimes;
import kr.govbenefit.common.security.AccessTokenIssuer.AccessToken;

/**
 * 로그인 결과. 액세스 토큰은 브라우저 메모리에만 두고 스크립트가 읽는 저장소에 넣지 않는다(DEC-AUTH).
 */
@Schema(description = "로그인 결과")
public record LoginResponse(
        @Schema(description = "액세스 토큰(JWT). `Authorization: Bearer <토큰>` 헤더로 보낸다.") String accessToken,
        @Schema(description = "토큰 종류. 항상 `Bearer`다.", example = "Bearer") String tokenType,
        @Schema(description = "만료 시각. 발급 후 15분이다. 지나면 개인 API가 401 `UNAUTHENTICATED`로 거절한다.",
                example = ApiTimes.EXAMPLE) OffsetDateTime expiresAt) {

    public static LoginResponse from(AccessToken token) {
        return new LoginResponse(token.value(), "Bearer", token.expiresAt().atZone(ApiTimes.ZONE).toOffsetDateTime());
    }
}
