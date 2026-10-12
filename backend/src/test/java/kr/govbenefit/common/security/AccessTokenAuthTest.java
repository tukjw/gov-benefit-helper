package kr.govbenefit.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 개인 API의 인증 경계(결정 0014·0015). 토큰 없음·위조·변조·만료는 모두 401 {@code UNAUTHENTICATED}이고,
 * 처리할 API가 없는 경로는 토큰과 관계없이 404다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccessTokenAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_account");
        aliceId = userAccountRepository.save(UserAccount.create("alice@example.com", "{noop}x", LocalDateTime.now())).getId();
        bobId = userAccountRepository.save(UserAccount.create("bob@example.com", "{noop}x", LocalDateTime.now())).getId();
    }

    @Test
    void 유효한_토큰이면_통과한다() throws Exception {
        String token = accessTokenIssuer.issue(aliceId).value();

        JsonNode body = json(me("Bearer " + token).andExpect(status().isOk()));
        assertThat(body.get("email").asString()).isEqualTo("alice@example.com");
    }

    @Test
    void 토큰이_없으면_거절한다() throws Exception {
        assertUnauthenticated(mockMvc.perform(get("/api/me")), false);
    }

    @Test
    void 형식이_틀린_헤더와_토큰은_거절한다() throws Exception {
        String token = accessTokenIssuer.issue(aliceId).value();

        // Bearer 없이 토큰만 보내면 토큰이 없는 요청이다.
        assertUnauthenticated(me(token), false);
        assertUnauthenticated(me("Basic " + token), false);
        assertUnauthenticated(me("Bearer not-a-jwt"), true);
        assertUnauthenticated(me("Bearer "), true);
    }

    @Test
    void 다른_키로_서명한_토큰은_거절한다() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        SecretKey key = new SecretKeySpec(otherKey, "HmacSHA256");
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        String forged = otherEncoder.encode(JwtEncoderParameters.from(header(), claims(aliceId).build())).getTokenValue();

        assertUnauthenticated(me("Bearer " + forged), true);
    }

    @Test
    void 서명을_유지한_채_내용을_바꾼_토큰은_거절한다() throws Exception {
        String[] parts = accessTokenIssuer.issue(aliceId).value().split("\\.");
        ObjectNode payload = (ObjectNode) jsonMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        payload.put("sub", String.valueOf(bobId));
        String tampered = parts[0] + "." + base64Url(jsonMapper.writeValueAsBytes(payload)) + "." + parts[2];

        assertUnauthenticated(me("Bearer " + tampered), true);
    }

    @Test
    void 서명_없는_토큰은_거절한다() throws Exception {
        String[] parts = accessTokenIssuer.issue(aliceId).value().split("\\.");
        String none = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        assertUnauthenticated(me("Bearer " + none + "." + parts[1] + "."), true);
    }

    @Test
    void 발급자나_용도가_다른_토큰은_거절한다() throws Exception {
        String otherIssuer = encode(claims(aliceId).issuer("someone-else").build());
        String noTokenUse = encode(claims(aliceId).claims(claims -> claims.remove(AccessTokenIssuer.TOKEN_USE_CLAIM)).build());
        String otherTokenUse = encode(claims(aliceId).claim(AccessTokenIssuer.TOKEN_USE_CLAIM, "email_verification").build());
        String noExpiry = encode(claims(aliceId).claims(claims -> claims.remove("exp")).build());

        assertUnauthenticated(me("Bearer " + otherIssuer), true);
        assertUnauthenticated(me("Bearer " + noTokenUse), true);
        assertUnauthenticated(me("Bearer " + otherTokenUse), true);
        assertUnauthenticated(me("Bearer " + noExpiry), true);
    }

    @Test
    void 발급_후_15분이_지난_토큰은_거절한다() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // 만료 2초 전: 요청 처리 시간을 감안한 여유
        String almostExpired = accessTokenIssuer.issue(aliceId, now.minusSeconds(15 * 60 - 2)).value();
        String expired = accessTokenIssuer.issue(aliceId, now.minusSeconds(15 * 60 + 1)).value();

        me("Bearer " + almostExpired).andExpect(status().isOk());
        assertUnauthenticated(me("Bearer " + expired), true);
    }

    @Test
    void 토큰_주인의_계정이_없으면_거절한다() throws Exception {
        String token = accessTokenIssuer.issue(aliceId).value();
        userAccountRepository.deleteById(aliceId);

        assertUnauthenticated(me("Bearer " + token), true);
    }

    @Test
    void 개인_API는_메서드가_달라도_인증을_먼저_요구한다() throws Exception {
        assertUnauthenticated(mockMvc.perform(post("/api/me")), false);
    }

    @Test
    void 없는_경로는_토큰과_관계없이_404다() throws Exception {
        String token = accessTokenIssuer.issue(aliceId).value();

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                get("/api/unknown"),
                get("/api/me/unknown"),
                get("/api/unknown").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)}) {
            JsonNode body = json(mockMvc.perform(request).andExpect(status().isNotFound()));
            assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(body.get("errors").size()).isZero();
        }
    }

    @Test
    void 공개_API는_토큰_없이_쓴다() throws Exception {
        mockMvc.perform(get("/api/benefits")).andExpect(status().isOk());
        mockMvc.perform(get("/api/benefits/NO-SUCH-ID")).andExpect(status().isNotFound());
    }

    /**
     * @param tokenSent 토큰을 보냈다가 거절됐으면 WWW-Authenticate에 {@code invalid_token}이 있다(RFC 6750).
     */
    private void assertUnauthenticated(ResultActions result, boolean tokenSent) throws Exception {
        result.andExpect(status().isUnauthorized());
        String authenticate = result.andReturn().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);
        // 디코딩 오류 문구나 제공하지 않는 메타데이터 주소를 내보내지 않는다.
        assertThat(authenticate).isEqualTo(tokenSent ? "Bearer error=\"invalid_token\"" : "Bearer");
        JsonNode body = json(result);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("errors").size()).isZero();
    }

    private JwtClaimsSet.Builder claims(Long userId) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .claim(AccessTokenIssuer.TOKEN_USE_CLAIM, AccessTokenIssuer.TOKEN_USE_ACCESS);
    }

    private String encode(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(header(), claims)).getTokenValue();
    }

    private static JwsHeader header() {
        return JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResultActions me(String authorization) throws Exception {
        return mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
