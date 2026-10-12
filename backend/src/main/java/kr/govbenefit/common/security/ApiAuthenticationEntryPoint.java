package kr.govbenefit.common.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증되지 않은 요청의 응답(결정 0015). 처리할 API가 없는 경로는 인증 여부와 관계없이 404 {@code NOT_FOUND}이고,
 * 그 밖에는 401 {@code UNAUTHENTICATED}다. 보안 필터는 {@code GlobalExceptionHandler}를 거치지 않으므로 본문을
 * 여기서 결정 0009 형식으로 쓴다.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final RequestMappingHandlerMapping handlerMapping;
    private final JsonMapper jsonMapper;

    public ApiAuthenticationEntryPoint(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            JsonMapper jsonMapper) {
        this.handlerMapping = handlerMapping;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (!hasHandler(request)) {
            write(response, ErrorCode.NOT_FOUND);
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, authenticateHeader(authException));
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    /**
     * 경로는 맞지만 메서드·미디어 타입이 다르면 조회가 예외를 던진다. 이때도 API가 있는 경로로 보고 401을 먼저 준다.
     */
    private boolean hasHandler(HttpServletRequest request) {
        // 보안 필터는 DispatcherServlet보다 먼저 돌아 경로가 아직 파싱되지 않았을 수 있다.
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        if (parsedHere) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        try {
            return handlerMapping.getHandler(request) != null;
        } catch (ServletException ex) {
            return true;
        } catch (Exception ex) {
            throw new IllegalStateException("요청 경로의 API를 찾지 못했습니다.", ex);
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    /**
     * RFC 6750의 최소 형식만 쓴다. 토큰을 보냈다가 거절되면 {@code invalid_token}이다. Spring 기본 entry point는 디코딩 오류
     * 문구({@code error_description})와 이 서버가 제공하지 않는 메타데이터 주소({@code resource_metadata}, RFC 9728)를 넣으므로
     * 쓰지 않는다.
     */
    static String authenticateHeader(AuthenticationException authException) {
        return authException instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer";
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(code));
    }
}
