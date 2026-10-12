package kr.govbenefit.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입 API를 Testcontainers MySQL로 검증한다. 이메일 중복은 실제 unique 제약과 정렬 규칙(결정 0001)을 따른다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SignUpApiTest {

    private static final String PASSWORD = "correct-horse";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    void 로그인_없이_가입하고_계정_정보를_받는다() throws Exception {
        JsonNode body = json(signUp("me@example.com", PASSWORD).andExpect(status().isCreated()));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("email", "createdAt");
        assertThat(body.get("email").asString()).isEqualTo("me@example.com");
        assertThat(body.get("createdAt").asString()).endsWith("+09:00");
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 비밀번호는_원문이_아니라_되돌릴_수_없는_해시로_저장한다() throws Exception {
        signUp("me@example.com", PASSWORD).andExpect(status().isCreated());

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_account WHERE email = 'me@example.com'", String.class);
        assertThat(stored).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
        assertThat(passwordEncoder.matches("wrong-horse", stored)).isFalse();
    }

    @Test
    void 이메일은_소문자로_저장한다() throws Exception {
        JsonNode body = json(signUp("Me@Example.COM", PASSWORD).andExpect(status().isCreated()));

        assertThat(body.get("email").asString()).isEqualTo("me@example.com");
        UserAccount account = userAccountRepository.findByEmail("me@example.com").orElseThrow();
        assertThat(account.getEmail()).isEqualTo("me@example.com");
    }

    @Test
    void 이미_가입된_이메일은_대소문자가_달라도_거절한다() throws Exception {
        signUp("me@example.com", PASSWORD).andExpect(status().isCreated());

        for (String email : List.of("me@example.com", "ME@EXAMPLE.COM")) {
            JsonNode body = json(signUp(email, "another-password").andExpect(status().isConflict()));
            assertThat(body.get("code").asString()).isEqualTo("DUPLICATE_EMAIL");
            assertThat(body.get("errors").size()).isZero();
        }
        assertThat(userAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 형식이_잘못된_이메일은_항목별_오류다() throws Exception {
        for (String email : List.of("not-an-email", "a@", "@example.com", "a b@example.com")) {
            assertInputError(signUp(email, PASSWORD), "email", email, "이메일 형식이 올바르지 않습니다.");
        }
        // 형식은 맞고 255자다. 도메인 이름표는 63자까지라 여러 개로 나눈다.
        String tooLong = "a@" + ("b".repeat(62) + ".").repeat(3) + "c".repeat(61) + ".kr";
        assertThat(tooLong).hasSize(255);
        assertInputError(signUp(tooLong, PASSWORD), "email", tooLong, "254자까지 입력할 수 있습니다.");
    }

    @Test
    void 빈_이메일은_항목별_오류다() throws Exception {
        assertInputError(signUp("", PASSWORD), "email", "", "이메일을 입력해야 합니다.");
        assertInputError(signUp(null, PASSWORD), "email", null, "이메일을 입력해야 합니다.");
    }

    @Test
    void 빈_비밀번호와_규칙에_맞지_않는_비밀번호는_값을_빼고_항목별_오류다() throws Exception {
        assertInputError(signUp("me@example.com", ""), "password", null, "8자 이상이어야 합니다.");
        assertInputError(signUp("me@example.com", null), "password", null, "비밀번호를 입력해야 합니다.");
        assertInputError(signUp("me@example.com", "short12"), "password", null, "8자 이상이어야 합니다.");
        assertInputError(signUp("me@example.com", "가".repeat(25)), "password", null, "72바이트 이하여야 합니다(한글은 24자).");
        assertThat(userAccountRepository.count()).isZero();
    }

    @Test
    void 이메일과_비밀번호가_모두_잘못되면_오류가_둘이다() throws Exception {
        JsonNode body = json(signUp("not-an-email", "").andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").valueStream().map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void 본문이_JSON이_아니면_잘못된_입력이다() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest()));

        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
    }

    private void assertInputError(ResultActions result, String field, String value, String reason) throws Exception {
        JsonNode body = json(result.andExpect(status().isBadRequest()));
        assertThat(body.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.get("errors").size()).as("errors: %s", body.get("errors")).isEqualTo(1);
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("field").asString()).isEqualTo(field);
        if (value == null) {
            assertThat(error.get("value").isNull()).as("value: %s", error.get("value")).isTrue();
        } else {
            assertThat(error.get("value").asString()).isEqualTo(value);
        }
        assertThat(error.get("reason").asString()).isEqualTo(reason);
    }

    private ResultActions signUp(String email, String password) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("email", email);
        request.put("password", password);
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(request)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
