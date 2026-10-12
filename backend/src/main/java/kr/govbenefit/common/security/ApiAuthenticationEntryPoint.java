package kr.govbenefit.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증되지 않은 요청의 401 {@code UNAUTHENTICATED} 응답(결정 0015). 보안 필터는 처리할 API가 있는지 모르므로 없는 경로도
 * 401이다. 보안 필터는 {@code GlobalExceptionHandler}를 거치지 않으므로 본문을 여기서 결정 0009 형식으로 쓴다.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    public ApiAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, authenticateHeader(authException));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(ErrorCode.UNAUTHENTICATED));
    }

    /**
     * RFC 6750의 최소 형식만 쓴다. 토큰을 보냈다가 거절되면 {@code invalid_token}이다. Spring 기본 entry point는 디코딩 오류
     * 문구({@code error_description})와 이 서버가 제공하지 않는 메타데이터 주소({@code resource_metadata}, RFC 9728)를 넣으므로
     * 쓰지 않는다.
     */
    static String authenticateHeader(AuthenticationException authException) {
        return authException instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer";
    }
}
