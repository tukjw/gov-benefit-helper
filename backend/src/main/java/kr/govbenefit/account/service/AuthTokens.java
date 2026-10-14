package kr.govbenefit.account.service;

import kr.govbenefit.common.security.AccessTokenIssuer.AccessToken;

/**
 * 로그인·재발급으로 함께 발급한 토큰. 리프레시 토큰은 응답 본문이 아니라 쿠키로만 보낸다(DEC-AUTH).
 *
 * @param accessToken  액세스 토큰
 * @param refreshToken 리프레시 토큰 원문
 */
public record AuthTokens(AccessToken accessToken, String refreshToken) {
}
