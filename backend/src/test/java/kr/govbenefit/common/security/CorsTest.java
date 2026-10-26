package kr.govbenefit.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.govbenefit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 브라우저가 프론트 출처에서 API를 직접 부를 때의 CORS(결정 0027). 허용 출처는 {@code auth.csrf.allowed-origins}다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsTest {

    private static final String ALLOWED = "https://govbenefit.kr";
    private static final String OTHER = "https://evil.example";
    private static final String PATH = "/api/benefits/regions";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 허용한_출처의_요청에는_허용_헤더가_붙는다() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void 허용한_출처의_사전_요청은_허용한_메서드와_헤더를_알려준다() throws Exception {
        mockMvc.perform(preflight(ALLOWED, "/api/auth/login", "POST", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"));
    }

    @Test
    void 인증이_필요한_경로도_사전_요청은_토큰_없이_통과한다() throws Exception {
        mockMvc.perform(preflight(ALLOWED, "/api/me/profile", "PUT", "authorization, content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    void 허용하지_않은_출처의_요청과_사전_요청은_403이다() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.ORIGIN, OTHER))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(preflight(OTHER, PATH, "GET", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void 허용하지_않은_헤더의_사전_요청은_403이다() throws Exception {
        mockMvc.perform(preflight(ALLOWED, PATH, "GET", "x-custom"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Origin이_없는_요청은_CORS와_관계없이_처리된다() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private static MockHttpServletRequestBuilder preflight(String origin, String path, String method, String headers) {
        return options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
    }
}
