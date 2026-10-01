package kr.govbenefit.collection.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kr.govbenefit.collection.domain.SourceEndpoint;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공공서비스(혜택) API 쪽 조회. 인증은 키가 URL에 남지 않도록 헤더로 한다.
 */
@Component
public class Gov24Client {

    private static final String AUTHORIZATION_PREFIX = "Infuser ";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 200;

    private final Gov24Properties properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public Gov24Client(Gov24Properties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .build();
    }

    /**
     * 한 쪽을 받는다. 200인데 {@code data}가 없거나, 200이 아니거나, 연결에 실패하면 {@link Gov24ApiException}을 던진다.
     */
    public Gov24Page fetchPage(SourceEndpoint endpoint, int page, int perPage) {
        String serviceKey = properties.requireServiceKey();
        Response response;
        try {
            response = restClient.get()
                    .uri(builder -> builder.path(endpoint.path())
                            .queryParam("page", page)
                            .queryParam("perPage", perPage)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION_PREFIX + serviceKey)
                    .exchange((request, clientResponse) -> new Response(
                            clientResponse.getStatusCode().value(),
                            StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8)), true);
        } catch (ResourceAccessException e) {
            throw new Gov24ApiException(describe(endpoint, page) + " 연결 실패: " + e.getMostSpecificCause().getClass()
                    .getSimpleName(), e);
        }
        return parse(endpoint, page, response);
    }

    private Gov24Page parse(SourceEndpoint endpoint, int page, Response response) {
        JsonNode body = readBody(response.body());
        if (response.status() != 200) {
            throw new Gov24ApiException(describe(endpoint, page) + " HTTP " + response.status() + errorDetail(body));
        }
        if (body == null || !body.path("data").isArray()) {
            // 응답이 너무 크면 200 {"code":0,"msg":"정상"}만 오고 data가 빠진다. 끝이 아니라 실패로 본다.
            throw new Gov24ApiException(describe(endpoint, page) + " 200 응답에 data가 없습니다" + errorDetail(body));
        }
        List<JsonNode> data = new ArrayList<>();
        body.get("data").forEach(data::add);
        return new Gov24Page(body.path("totalCount").asInt(0), data);
    }

    private JsonNode readBody(String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String errorDetail(JsonNode body) {
        if (body == null || !body.has("code")) {
            return "";
        }
        String msg = body.path("msg").asString("");
        if (msg.length() > ERROR_MESSAGE_MAX_LENGTH) {
            msg = msg.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        }
        return " (code=" + body.path("code").asString("") + ", msg=" + msg + ")";
    }

    private static String describe(SourceEndpoint endpoint, int page) {
        return endpoint.path() + " page=" + page;
    }

    private record Response(int status, String body) {
    }
}
