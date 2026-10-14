package kr.govbenefit.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 쿠키로 인증하는 요청의 CSRF 방어(결정 0017). 컨트롤러보다 먼저 거절해, 막지 못한 요청이 토큰 교체나 재사용 감지를 일으키지 않게 한다.
 *
 * <ul>
 *     <li>{@value #REQUIRED_HEADER} 헤더가 있어야 한다. 사이트를 넘는 폼 전송은 헤더를 붙일 수 없고, 다른 출처의 {@code fetch}가
 *     헤더를 붙이면 CORS preflight가 걸린다.</li>
 *     <li>{@code Origin} 헤더가 있으면 허용 목록에 있어야 한다. {@code SameSite}가 막지 못하는 같은 사이트의 다른 서브도메인을 막는다.
 *     {@code Origin}이 없는 요청(브라우저가 아닌 클라이언트)은 헤더 검사만 한다.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class CsrfOriginInterceptor implements HandlerInterceptor {

    public static final String REQUIRED_HEADER = "X-Requested-With";

    private final CsrfProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(REQUIRED_HEADER);
        if (header == null || header.isBlank()) {
            throw new BusinessException(ErrorCode.CSRF_REJECTED, REQUIRED_HEADER + " 헤더 없음");
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !properties.allowedOrigins().contains(origin)) {
            throw new BusinessException(ErrorCode.CSRF_REJECTED, "허용하지 않은 출처: " + origin);
        }
        return true;
    }
}
