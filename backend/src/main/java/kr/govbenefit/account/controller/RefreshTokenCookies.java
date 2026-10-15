package kr.govbenefit.account.controller;

import kr.govbenefit.account.config.RefreshTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키(DEC-AUTH, 결정 0017). 스크립트가 읽을 수 없고(HttpOnly), HTTPS로만 보내며, 사이트 밖에서 시작한 요청에는
 * 붙지 않는다(SameSite=Strict). 경로를 {@code /api/auth}로 좁혀 개인 API 요청에는 싣지 않는다. 그래서 {@code Path=/}가 필요한
 * {@code __Host-} 접두사는 쓰지 않는다. {@code Domain}을 지정하지 않아 API 호스트에만 저장된다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/auth";

    private final RefreshTokenProperties properties;

    public String issue(String refreshToken) {
        return builder(refreshToken).maxAge(properties.ttl()).build().toString();
    }

    /** 같은 이름·경로로 Max-Age=0을 보내 브라우저의 쿠키를 지운다. */
    public String clear() {
        return builder("").maxAge(0).build().toString();
    }

    private static ResponseCookie.ResponseCookieBuilder builder(String value) {
        // 브라우저는 http://localhost를 보안 컨텍스트로 보므로 local에서도 Secure를 끄지 않는다.
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
