package kr.govbenefit.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.common.security.AccessTokenIssuer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * local이 아닌 프로필에서는 명세와 문서 화면이 열리지 않는다(결정 0012).
 * webjars 경로는 Spring Boot 기본 정적 매핑이 springdoc 설정과 무관하게 여는 경로다. 공개 경로가 아니라 토큰이 없으면 401이므로
 * 유효한 토큰으로 요청해 처리할 핸들러가 없는지(404) 본다(결정 0015).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsClosedTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @ParameterizedTest
    @ValueSource(strings = {
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            "/webjars/swagger-ui/index.html"})
    void 문서_경로가_열리지_않는다(String path) throws Exception {
        String token = accessTokenIssuer.issue(1L).value();

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
