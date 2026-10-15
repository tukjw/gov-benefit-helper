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
 * 설명 문장은 리뷰로 확인하고, 여기서는 설명이 있는지만 본다.
 */
@SpringBootTest
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

        assertThat(names(parameters)).containsExactly("keyword", "field", "agencyType", "page", "size");
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
                new String[] {"Detail", "onlineApplyUrl"},
                new String[] {"Freshness", "stale"},
                new String[] {"Freshness", "referenceAt"});
        described.forEach(target -> assertThat(property(target[0], target[1]).path("description").asString())
                .as("%s.%s", target[0], target[1])
                .isNotBlank());
        assertThat(schema("BenefitDetailResponse").path("description").asString()).isNotBlank();
    }

    @Test
    void 가입_API의_요청과_응답() {
        JsonNode operation = spec.path("paths").path("/api/auth/signup").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref")
                .asString()).isEqualTo("#/components/schemas/SignUpRequest");
        assertThat(texts(schema("SignUpRequest").path("required"))).containsExactlyInAnyOrder("email", "password");
        assertThat(property("SignUpRequest", "password").path("description").asString()).contains("72바이트");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("201", "400", "409", "500");
        assertThat(schemaRef(responses, "201")).isEqualTo("#/components/schemas/AccountResponse");
        for (String code : List.of("400", "409", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
    }

    @Test
    void 로그인_API의_요청과_응답() {
        JsonNode operation = spec.path("paths").path("/api/auth/login").path("post");

        assertThat(operation.path("security").isMissingNode()).isTrue();
        assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref")
                .asString()).isEqualTo("#/components/schemas/LoginRequest");

        JsonNode responses = operation.path("responses");
        assertThat(names(responses)).containsExactlyInAnyOrder("200", "400", "401", "500");
        assertThat(schemaRef(responses, "200")).isEqualTo("#/components/schemas/LoginResponse");
        for (String code : List.of("400", "401", "500")) {
            assertThat(schemaRef(responses, code)).as(code).isEqualTo(ERROR_REF);
        }
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
                new String[] {"Freshness", "referenceAt"},
                new String[] {"AccountResponse", "createdAt"},
                new String[] {"LoginResponse", "expiresAt"});
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
