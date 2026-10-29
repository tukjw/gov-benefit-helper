package kr.govbenefit.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * local 프로필의 OpenAPI 명세와 문서 화면(결정 0012). 실제 application-local.yaml로 켜지는지 함께 확인한다.
 * 설명 문장은 리뷰로 확인하고, 여기서는 설명이 있는지만 본다. 메일은 local의 로그 발송 대신 테스트의 가짜 발송만 쓴다(결정 0020).
 */
@SpringBootTest(properties = "auth.mail.sender=test")
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsTest {

    private static final String ERROR_REF = "#/components/schemas/ApiErrorResponse";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-2b7e91");
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private JsonNode spec;

    @BeforeEach
    void loadSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        spec = jsonMapper.readTree(body);
    }

    @AfterEach
    void 공공_API를_호출하지_않는다() {
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void 문서_화면이_열린다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        // 화면이 명세 주소를 받아 가는 설정
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void 검색_API의_파라미터와_제약() {
        JsonNode operation = spec.path("paths").path("/api/benefits").path("get");
        JsonNode parameters = operation.path("parameters");

        assertThat(names(parameters)).containsExactly("keyword", "field", "agencyType", "agencySidoCode",
                "agencySigunguCode", "page", "size");
        parameters.forEach(parameter -> {
            assertThat(parameter.path("in").asString()).isEqualTo("query");
            assertThat(parameter.path("required").asBoolean()).isFalse();
        });

        assertThat(parameter(parameters, "keyword").path("schema").path("maxLength").asInt()).isEqualTo(100);
        for (String filter : List.of("field", "agencyType")) {
            JsonNode schema = parameter(parameters, filter).path("schema");
            assertThat(schema.path("type").asString()).isEqualTo("array");
            // 서버가 값을 검증하지 않으므로 허용값 목록을 두지 않는다(결정 0012).
            assertThat(schema.path("items").has("enum")).isFalse();
            assertThat(parameter(parameters, filter).path("description").asString()).isNotBlank();
        }
        for (String region : List.of("agencySidoCode", "agencySigunguCode")) {
            assertThat(parameter(parameters, region).path("schema").path("type").asString()).isEqualTo("string");
            assertThat(parameter(parameters, region).path("description").asString()).contains("거주 요건이 아니다");
        }
        JsonNode page = parameter(parameters, "page").path("schema");
        assertThat(page.path("type").asString()).isEqualTo("integer");
        assertThat(page.path("minimum").asInt()).isEqualTo(1);
        assertThat(page.path("default").asInt()).isEqualTo(1);
        JsonNode size = parameter(parameters, "size").path("schema");
        assertThat(size.path("type").asString()).isEqualTo("integer");
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(100);
        assertThat(size.path("default").asInt()).isEqualTo(20);
    }

    @Test
    void 지역_목록_API는_로그인_없이_쓴다() {
        JsonNode operation = spec.path("paths").path("/api/benefits/regions").path("get");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(operation.path("parameters").isMissingNode() || operation.path("parameters").isEmpty()).isTrue();
        assertThat(schemaRef(operation.path("responses"), "200"))
                .isEqualTo("#/components/schemas/RegionListResponse");
        assertThat(names(schema("SidoOption").path("properties"))).containsExactlyInAnyOrder("code", "name",
                "sigungus");
        assertThat(names(schema("RegionResponse").path("properties"))).containsExactlyInAnyOrder("code", "name");
    }

    @Test
    void 검색_API의_응답() {
        JsonNode responses = spec.path("paths").path("/api/benefits").path("get").path("responses");

        assertThat(names(responses)).containsExactlyInAnyOrder("200", "400", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/BenefitSearchResponse");
        assertThat(schemaRef(responses, "400")).isEqualTo(ERROR_REF);
        assertThat(schemaRef(responses, "500")).isEqualTo(ERROR_REF);

        assertThat(property("BenefitSearchResponse", "totalCount").path("description").asString())
                .contains("의존하지");
    }

    @Test
    void 상세_API의_파라미터와_응답() {
        JsonNode operation = spec.path("paths").path("/api/benefits/{serviceId}").path("get");

        JsonNode serviceId = parameter(operation.path("parameters"), "serviceId");
        assertThat(serviceId.path("in").asString()).isEqualTo("path");
        assertThat(serviceId.path("required").asBoolean()).isTrue();
        assertThat(serviceId.path("schema").path("type").asString()).isEqualTo("string");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "404", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/BenefitDetailResponse");
        assertThat(schemaRef(responses, "404")).isEqualTo(ERROR_REF);
        assertThat(schemaRef(responses, "500")).isEqualTo(ERROR_REF);
    }

    @Test
    void 상세_응답의_의미가_설명에_있다() {
        List<String[]> described = List.of(
                new String[] {"BenefitDetailResponse", "detail"},
                new String[] {"BenefitDetailResponse", "sourceModifiedAt"},
                new String[] {"BenefitDetailResponse", "lastSeenAt"},
                new String[] {"ApplyPeriod", "needsReview"},
                new String[] {"Detail", "onlineApplyUrl"});
        described.forEach(target -> assertThat(property(target[0], target[1]).path("description").asString())
                .as("%s.%s", target[0], target[1])
                .isNotBlank());
        assertThat(schema("BenefitDetailResponse").path("description").asString()).isNotBlank();
        assertThat(spec.path("components").path("schemas").has("Freshness")).isFalse();
    }

    @Test
    void 가입_API의_요청과_응답() {
        JsonNode operation = spec.path("paths").path("/api/auth/signup").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref")
                .asString()).isEqualTo("#/components/schemas/SignUpRequest");
        assertThat(texts(schema("SignUpRequest").path("required")))
                .containsExactlyInAnyOrder("email", "password", "verificationToken");
        assertThat(property("SignUpRequest", "password").path("description").asString()).contains("72바이트");
        assertThat(property("SignUpRequest", "verificationToken").path("description").asString())
                .contains("verificationToken");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("201", "400", "403", "409", "500");
        assertThat(schemaRef(responses, "201")).isEqualTo("#/components/schemas/AccountResponse");
        for (String code : List.of("400", "403", "409", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 인증번호_요청_API는_본문_없이_응답하고_발송_제한을_알린다() {
        JsonNode operation = spec.path("paths").path("/api/auth/email-verification").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(requestRef(operation)).isEqualTo("#/components/schemas/EmailVerificationRequest");
        assertThat(texts(schema("EmailVerificationRequest").path("required"))).containsExactly("email");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("204", "400", "429", "500");
        assertThat(responses.path("204").path("content").isMissingNode()).isTrue();
        assertThat(responses.path("429").path("headers").has("Retry-After")).isTrue();
        for (String code : List.of("400", "429", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 인증번호_확인_API는_가입_티켓을_준다() {
        JsonNode operation = spec.path("paths").path("/api/auth/email-verification/confirm").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(requestRef(operation)).isEqualTo("#/components/schemas/EmailVerificationConfirmRequest");
        assertThat(texts(schema("EmailVerificationConfirmRequest").path("required")))
                .containsExactlyInAnyOrder("email", "code");
        assertThat(property("EmailVerificationConfirmRequest", "code").path("pattern").asString())
                .isEqualTo("\\d{6}");
        assertThat(operation.path("description").asString())
                .contains("VERIFICATION_CODE_MISMATCH", "VERIFICATION_CODE_EXPIRED", "VERIFICATION_ATTEMPTS_EXCEEDED");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "400", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/EmailVerificationConfirmResponse");
        assertThat(names(schema("EmailVerificationConfirmResponse").path("properties")))
                .containsExactlyInAnyOrder("verificationToken", "expiresAt");
        for (String code : List.of("400", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 비밀번호_재설정_요청_API는_가입_여부와_관계없이_본문_없이_응답한다() {
        JsonNode operation = spec.path("paths").path("/api/auth/password-reset").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(requestRef(operation)).isEqualTo("#/components/schemas/PasswordResetRequest");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("204", "400", "500");
        assertThat(responses.path("204").path("content").isMissingNode()).isTrue();
        assertThat(operation.path("description").asString()).contains("가입 여부를 드러내지 않는다");
    }

    @Test
    void 비밀번호_재설정_API는_토큰과_새_비밀번호를_받는다() {
        JsonNode operation = spec.path("paths").path("/api/auth/password-reset/confirm").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(requestRef(operation)).isEqualTo("#/components/schemas/PasswordResetConfirmRequest");
        assertThat(texts(schema("PasswordResetConfirmRequest").path("required")))
                .containsExactlyInAnyOrder("token", "newPassword");
        assertThat(property("PasswordResetConfirmRequest", "newPassword").path("description").asString())
                .contains("72바이트");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("204", "400", "500");
        assertThat(responses.path("204").path("content").isMissingNode()).isTrue();
        assertThat(schemaRef(responses, "400")).isEqualTo(ERROR_REF);
    }

    @Test
    void 로그인_API의_요청과_응답() {
        JsonNode operation = spec.path("paths").path("/api/auth/login").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref")
                .asString()).isEqualTo("#/components/schemas/LoginRequest");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "400", "401", "429", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/LoginResponse");
        for (String code : List.of("400", "401", "429", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
        assertThat(responses.path("429").path("headers").has("Retry-After")).isTrue();
        assertThat(property("LoginResponse", "expiresAt").path("description").asString()).contains("15분");
        assertThat(responses.path("200").path("headers").has("Set-Cookie")).isTrue();
    }

    @Test
    void 재발급_API는_쿠키와_CSRF_헤더를_받고_새_쿠키를_준다() {
        JsonNode operation = spec.path("paths").path("/api/auth/refresh").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        JsonNode parameters = operation.path("parameters");
        assertThat(names(parameters)).containsExactlyInAnyOrder("refresh_token", "X-Requested-With");
        assertThat(parameter(parameters, "refresh_token").path("in").asString()).isEqualTo("cookie");
        assertThat(parameter(parameters, "X-Requested-With").path("in").asString()).isEqualTo("header");
        assertThat(parameter(parameters, "X-Requested-With").path("required").asBoolean()).isTrue();

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "401", "403", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/LoginResponse");
        assertThat(responses.path("200").path("headers").has("Set-Cookie")).isTrue();
        for (String code : List.of("401", "403", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 로그아웃_API는_쿠키를_받고_본문_없이_응답한다() {
        JsonNode operation = spec.path("paths").path("/api/auth/logout").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(names(operation.path("parameters"))).containsExactly("refresh_token");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("204", "500");
        assertThat(responses.path("204").path("content").isMissingNode()).isTrue();
        assertThat(responses.path("204").path("headers").has("Set-Cookie")).isTrue();
        assertThat(schemaRef(responses, "500")).isEqualTo(ERROR_REF);
    }

    @Test
    void 내_정보_API는_액세스_토큰을_요구하고_사용자_ID를_받지_않는다() {
        JsonNode operation = spec.path("paths").path("/api/me").path("get");

        assertThat(operation.path("parameters").isMissingNode() || operation.path("parameters").isEmpty()).isTrue();
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "401", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/AccountResponse");
        assertThat(schemaRef(responses, "401")).isEqualTo(ERROR_REF);
        assertThat(names(schema("AccountResponse").path("properties"))).containsExactlyInAnyOrder("email", "createdAt");
    }

    @Test
    void 탈퇴_API는_액세스_토큰과_비밀번호만_받고_쿠키를_지운다() {
        JsonNode operation = spec.path("paths").path("/api/me/withdrawal").path("post");

        assertThat(operation.path("parameters").isMissingNode() || operation.path("parameters").isEmpty()).isTrue();
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(requestRef(operation)).isEqualTo("#/components/schemas/WithdrawalRequest");
        assertThat(names(schema("WithdrawalRequest").path("properties"))).containsExactly("password");
        assertThat(texts(schema("WithdrawalRequest").path("required"))).containsExactly("password");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("204", "400", "401", "500");
        assertThat(responses.path("204").path("content").isMissingNode()).isTrue();
        assertThat(responses.path("204").path("headers").has("Set-Cookie")).isTrue();
        for (String code : List.of("400", "401", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 프로필_API는_액세스_토큰을_요구하고_사용자_ID를_받지_않는다() {
        JsonNode get = spec.path("paths").path("/api/me/profile").path("get");
        JsonNode put = spec.path("paths").path("/api/me/profile").path("put");

        for (JsonNode operation : List.of(get, put)) {
            assertThat(operation.path("parameters").isMissingNode() || operation.path("parameters").isEmpty()).isTrue();
            assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(schemaRef(operation.path("responses"), "401")).isEqualTo(ERROR_REF);
        }
        assertThat(names(get.path("responses"))).containsExactlyInAnyOrder("200", "401", "500");
        assertThat(names(put.path("responses"))).containsExactlyInAnyOrder("200", "400", "401", "500");
        assertThat(schemaRef(get.path("responses"), "200")).isEqualTo("#/components/schemas/ProfileResponse");
        assertThat(schemaRef(put.path("responses"), "200")).isEqualTo("#/components/schemas/ProfileResponse");
        assertThat(schemaRef(put.path("responses"), "400")).isEqualTo(ERROR_REF);
        assertThat(requestRef(put)).isEqualTo("#/components/schemas/ProfileRequest");
    }

    @Test
    void 프로필_선택지는_로그인_없이_받는다() {
        JsonNode options = spec.path("paths").path("/api/profile/options").path("get");

        assertThat(options.path("security").isMissingNode()).isTrue();
        assertThat(names(options.path("responses"))).containsExactlyInAnyOrder("200", "500");
        assertThat(schemaRef(options.path("responses"), "200")).isEqualTo("#/components/schemas/ProfileOptionsResponse");
        assertThat(spec.path("paths").has("/api/me/profile/options")).isFalse();
    }

    @Test
    void 프로필_요청은_범위_안의_항목만_받고_허용_값을_알린다() {
        assertThat(names(schema("ProfileRequest").path("properties"))).containsExactlyInAnyOrder("birthDate", "gender",
                "sidoCode", "sigunguCode", "lifeAttributes", "householdAttributes");
        assertThat(texts(property("ProfileRequest", "gender").path("enum"))).containsExactly("MALE", "FEMALE");
        assertThat(texts(property("ProfileRequest", "lifeAttributes").path("items").path("enum")))
                .containsExactlyElementsOf(Arrays.stream(LifeAttribute.values()).map(Enum::name).toList());
        assertThat(texts(property("ProfileRequest", "householdAttributes").path("items").path("enum")))
                .containsExactlyElementsOf(Arrays.stream(HouseholdAttribute.values()).map(Enum::name).toList());
        assertThat(names(schema("ProfileResponse").path("properties"))).containsExactlyInAnyOrder("birthDate", "age",
                "gender", "sido", "sigungu", "lifeAttributes", "householdAttributes", "updatedAt");
        assertThat(names(schema("ProfileOptionsResponse").path("properties")))
                .containsExactlyInAnyOrder("sidos", "lifeAttributes", "householdAttributes");
    }

    @Test
    void 북마크_API는_액세스_토큰을_요구하고_사용자_ID를_받지_않는다() {
        JsonNode save = spec.path("paths").path("/api/me/bookmarks/{serviceId}").path("put");
        JsonNode remove = spec.path("paths").path("/api/me/bookmarks/{serviceId}").path("delete");
        JsonNode list = spec.path("paths").path("/api/me/bookmarks").path("get");

        for (JsonNode operation : List.of(save, remove, list)) {
            assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(names(operation.path("parameters"))).doesNotContain("userId");
            assertThat(operation.has("requestBody")).isFalse();
            assertThat(schemaRef(operation.path("responses"), "401")).isEqualTo(ERROR_REF);
        }
        for (JsonNode operation : List.of(save, remove)) {
            JsonNode serviceId = parameter(operation.path("parameters"), "serviceId");
            assertThat(serviceId.path("in").asString()).isEqualTo("path");
            assertThat(serviceId.path("schema").path("type").asString()).isEqualTo("string");
            assertThat(operation.path("responses").path("204").path("content").isMissingNode()).isTrue();
        }
        assertThat(names(save.path("responses"))).containsExactlyInAnyOrder("204", "401", "404", "500");
        assertThat(schemaRef(save.path("responses"), "404")).isEqualTo(ERROR_REF);
        assertThat(names(remove.path("responses"))).containsExactlyInAnyOrder("204", "401", "500");

        assertThat(names(list.path("parameters"))).containsExactly("page", "size");
        assertThat(parameter(list.path("parameters"), "size").path("schema").path("maximum").asInt()).isEqualTo(100);
        assertThat(names(list.path("responses"))).containsExactlyInAnyOrder("200", "400", "401", "500");
        assertThat(schemaRef(list.path("responses"), "200")).isEqualTo("#/components/schemas/BookmarkListResponse");
        assertThat(schemaRef(list.path("responses"), "400")).isEqualTo(ERROR_REF);
        assertThat(names(schema("BookmarkListResponse").path("properties")))
                .containsExactlyInAnyOrder("totalCount", "page", "size", "items");
        assertThat(names(schema("BookmarkItem").path("properties"))).containsExactlyInAnyOrder("bookmarkedAt", "benefit");
    }

    @Test
    void 조건_비교_API는_액세스_토큰을_요구하고_사용자_ID를_받지_않는다() {
        JsonNode match = spec.path("paths").path("/api/me/matches/{serviceId}").path("get");

        assertThat(match.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(names(match.path("parameters"))).containsExactly("serviceId");
        assertThat(parameter(match.path("parameters"), "serviceId").path("in").asString()).isEqualTo("path");
        assertThat(match.has("requestBody")).isFalse();
        assertThat(names(match.path("responses"))).containsExactlyInAnyOrder("200", "401", "404", "500");
        assertThat(schemaRef(match.path("responses"), "200")).isEqualTo("#/components/schemas/MatchResponse");
        assertThat(schemaRef(match.path("responses"), "401")).isEqualTo(ERROR_REF);
        assertThat(schemaRef(match.path("responses"), "404")).isEqualTo(ERROR_REF);
        assertThat(names(schema("MatchResponse").path("properties"))).containsExactlyInAnyOrder("serviceId", "name",
                "result", "judgedOn", "notices", "uncomparedConditions", "items", "source");
        assertThat(texts(property("MatchResponse", "result").path("enum")))
                .containsExactly("MATCH", "NEEDS_CHECK", "MISMATCH");
        assertThat(names(schema("MatchItems").path("properties"))).containsExactlyInAnyOrder("personalTarget", "age",
                "gender", "lifeAttributes", "householdAttributes", "agencyRegion");
        assertThat(names(schema("AgencyRegionItem").path("properties"))).containsExactlyInAnyOrder("status",
                "reason", "agencyType", "agencyRegion", "profileRegion");
        assertThat(names(schema("RegionValue").path("properties"))).containsExactlyInAnyOrder("sido", "sigungu");
        assertThat(schema("AgencyRegionItem").path("description").asString()).contains("거주 요건이 아니라");
        assertThat(names(schema("MatchSource").path("properties")))
                .containsExactlyInAnyOrder("target", "selectionCriteria", "guideUrl");
        assertThat(schema("MatchResponse").path("description").asString()).contains("신청 가능 확정이 아니다");
    }

    @Test
    void 조건_비교_목록_API는_액세스_토큰을_요구하고_지역_범위와_쪽_번호와_크기만_받는다() {
        JsonNode list = spec.path("paths").path("/api/me/matches").path("get");

        assertThat(list.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(names(list.path("parameters"))).containsExactly("regionScope", "page", "size");
        assertThat(texts(parameter(list.path("parameters"), "regionScope").path("schema").path("enum")))
                .containsExactly("MY_REGION", "ALL");
        assertThat(parameter(list.path("parameters"), "size").path("schema").path("maximum").asInt()).isEqualTo(100);
        assertThat(names(list.path("responses"))).containsExactlyInAnyOrder("200", "400", "401", "500");
        assertThat(schemaRef(list.path("responses"), "200")).isEqualTo("#/components/schemas/MatchListResponse");
        assertThat(schemaRef(list.path("responses"), "400")).isEqualTo(ERROR_REF);
        assertThat(schemaRef(list.path("responses"), "401")).isEqualTo(ERROR_REF);
        assertThat(names(schema("MatchListResponse").path("properties"))).containsExactlyInAnyOrder("totalCount",
                "matchCount", "needsCheckCount", "regionScope", "page", "size", "judgedOn", "notices",
                "uncomparedConditions", "items");
        assertThat(names(schema("MatchListItem").path("properties")))
                .containsExactlyInAnyOrder("benefit", "result", "items");
        assertThat(names(schema("MatchItemResults").path("properties"))).containsExactlyInAnyOrder("personalTarget",
                "age", "gender", "lifeAttributes", "householdAttributes", "agencyRegion");
        assertThat(names(schema("MatchItemResult").path("properties"))).containsExactlyInAnyOrder("status", "reason");
        assertThat(schema("MatchListResponse").path("description").asString()).contains("신청 가능 확정이 아니다");
    }

    @Test
    void 비회원_조건_비교_API는_로그인_없이_프로필_값을_본문으로_받고_같은_응답을_준다() {
        JsonNode list = spec.path("paths").path("/api/matches").path("post");
        JsonNode match = spec.path("paths").path("/api/matches/{serviceId}").path("post");

        for (JsonNode operation : List.of(list, match)) {
            assertThat(operation.path("security").isMissingNode()).isTrue();
            assertThat(requestRef(operation)).isEqualTo("#/components/schemas/ProfileRequest");
            assertThat(operation.path("requestBody").path("required").asBoolean()).isFalse();
            assertThat(schemaRef(operation.path("responses"), "400")).isEqualTo(ERROR_REF);
        }
        assertThat(names(list.path("parameters"))).containsExactly("regionScope", "page", "size");
        assertThat(names(list.path("responses"))).containsExactlyInAnyOrder("200", "400", "500");
        assertThat(schemaRef(list.path("responses"), "200")).isEqualTo("#/components/schemas/MatchListResponse");
        assertThat(names(match.path("parameters"))).containsExactly("serviceId");
        assertThat(names(match.path("responses"))).containsExactlyInAnyOrder("200", "400", "404", "500");
        assertThat(schemaRef(match.path("responses"), "200")).isEqualTo("#/components/schemas/MatchResponse");
        assertThat(schemaRef(match.path("responses"), "404")).isEqualTo(ERROR_REF);
    }

    @Test
    void 액세스_토큰_인증_방식이_명세에_있다() {
        JsonNode bearer = spec.path("components").path("securitySchemes").path("bearerAuth");

        assertThat(bearer.path("type").asString()).isEqualTo("http");
        assertThat(bearer.path("scheme").asString()).isEqualTo("bearer");
        assertThat(bearer.path("bearerFormat").asString()).isEqualTo("JWT");
    }

    @Test
    void 오류_응답_스키마가_결정_0009_형식이다() {
        JsonNode error = schema("ApiErrorResponse");

        assertThat(texts(error.path("required"))).containsExactlyInAnyOrder("code", "message", "errors");
        assertThat(texts(property("ApiErrorResponse", "code").path("enum")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(property("ApiErrorResponse", "message").path("type").asString()).isEqualTo("string");

        JsonNode errors = property("ApiErrorResponse", "errors");
        assertThat(errors.path("type").asString()).isEqualTo("array");
        assertThat(errors.path("items").path("$ref").asString()).isEqualTo("#/components/schemas/InputError");
        assertThat(names(schema("InputError").path("properties"))).containsExactlyInAnyOrder("field", "value", "reason");
    }

    @Test
    void 시각_필드는_오프셋을_포함한_형식이다() {
        List<String[]> times = List.of(
                new String[] {"BenefitSummary", "lastSeenAt"},
                new String[] {"BenefitDetailResponse", "sourceModifiedAt"},
                new String[] {"BenefitDetailResponse", "lastSeenAt"},
                new String[] {"AccountResponse", "createdAt"},
                new String[] {"LoginResponse", "expiresAt"},
                new String[] {"EmailVerificationConfirmResponse", "expiresAt"},
                new String[] {"ProfileResponse", "updatedAt"},
                new String[] {"BookmarkItem", "bookmarkedAt"});
        times.forEach(target -> {
            JsonNode time = property(target[0], target[1]);
            assertThat(time.path("type").asString()).as("%s.%s", target[0], target[1]).isEqualTo("string");
            assertThat(time.path("format").asString()).as("%s.%s", target[0], target[1]).isEqualTo("date-time");
            assertThat(time.path("example").asString()).as("%s.%s", target[0], target[1]).endsWith("+09:00");
        });
    }

    private JsonNode schema(String name) {
        JsonNode schema = spec.path("components").path("schemas").path(name);
        assertThat(schema.isMissingNode()).as(name).isFalse();
        return schema;
    }

    private JsonNode property(String schemaName, String propertyName) {
        JsonNode property = schema(schemaName).path("properties").path(propertyName);
        assertThat(property.isMissingNode()).as("%s.%s", schemaName, propertyName).isFalse();
        return property;
    }

    private static JsonNode parameter(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (parameter.path("name").asString().equals(name)) {
                return parameter;
            }
        }
        throw new AssertionError("파라미터가 없다: " + name);
    }

    private static String requestRef(JsonNode operation) {
        return operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref")
                .asString();
    }

    private static String schemaRef(JsonNode responses, String code) {
        return responses.path(code).path("content").path("application/json").path("schema").path("$ref").asString();
    }

    /** 배열이면 각 항목의 {@code name}, 객체면 키 */
    private static List<String> names(JsonNode node) {
        if (node.isArray()) {
            List<String> names = new ArrayList<>();
            node.forEach(item -> names.add(item.path("name").asString()));
            return names;
        }
        return new ArrayList<>(node.propertyNames());
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asString()));
        return texts;
    }
}
