package kr.govbenefit.collection.client;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.govbenefit.collection.domain.CollectionErrorType;
import kr.govbenefit.collection.domain.SourceEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공공서비스(혜택) API 쪽 조회. 인증은 키가 URL에 남지 않도록 헤더로 한다.
 * 시간 제한과 재시도 상한을 두고, 재시도를 포함한 모든 시도는 실행의 호출 예산에서 차감한다(결정 0006).
 */
@Slf4j
@Component
public class Gov24Client {

    private static final String AUTHORIZATION_PREFIX = "Infuser ";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 200;
    private static final double RETRY_MULTIPLIER = 2;

    private final Gov24Properties properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    public Gov24Client(Gov24Properties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(properties.retryDelay())
                .multiplier(RETRY_MULTIPLIER)
                .predicate(e -> e instanceof Gov24ApiException failure && failure.type().retryable())
                .build());
    }

    /**
     * 한 쪽을 받는다. 시간 초과·연결 실패·5xx는 재시도 상한까지 다시 시도하고, 그 밖의 실패는 바로 던진다.
     *
     * @throws Gov24ApiException 받지 못했을 때. 유형과 이 쪽에 보낸 시도 횟수를 담는다
     */
    public Gov24Page fetchPage(SourceEndpoint endpoint, int page, int perPage, Gov24CallBudget budget) {
        String serviceKey = properties.requireServiceKey();
        AtomicInteger attempts = new AtomicInteger();
        try {
            return retryTemplate.execute(() -> {
                budget.acquire();
                int attempt = attempts.incrementAndGet();
                try {
                    return fetchOnce(endpoint, page, perPage, serviceKey, attempt);
                } catch (Gov24ApiException e) {
                    log.warn("공공 API 요청 실패 errorType={} endpoint={} page={} attempt={} message={}", e.type(),
                            endpoint, page, attempt, e.getMessage());
                    throw e;
                }
            });
        } catch (RetryException e) {
            if (e.getLastException() instanceof Gov24ApiException failure) {
                throw failure.withAttempts(attempts.get());
            }
            throw new IllegalStateException(describe(endpoint, page) + " 처리 중 예상하지 못한 오류", e.getLastException());
        }
    }

    private Gov24Page fetchOnce(SourceEndpoint endpoint, int page, int perPage, String serviceKey, int attempt) {
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
            CollectionErrorType type = Gov24ErrorClassifier.ofIoFailure(e);
            throw new Gov24ApiException(type, describe(endpoint, page) + (type == CollectionErrorType.API_TIMEOUT
                    ? " 시간 초과: " : " 연결 실패: ") + e.getMostSpecificCause().getClass().getSimpleName(), e);
        }
        return parse(endpoint, page, response, attempt);
    }

    private Gov24Page parse(SourceEndpoint endpoint, int page, Response response, int attempt) {
        JsonNode body = readBody(response.body());
        if (response.status() != 200) {
            throw new Gov24ApiException(Gov24ErrorClassifier.ofStatus(response.status()),
                    describe(endpoint, page) + " HTTP " + response.status() + errorDetail(body));
        }
        if (body == null || !body.path("data").isArray()) {
            // 응답이 너무 크면 200 {"code":0,"msg":"정상"}만 오고 data가 빠진다. 끝이 아니라 실패로 본다.
            throw new Gov24ApiException(CollectionErrorType.API_BAD_RESPONSE,
                    describe(endpoint, page) + " 200 응답에 data가 없습니다" + errorDetail(body));
        }
        List<JsonNode> data = new ArrayList<>();
        body.get("data").forEach(data::add);
        return new Gov24Page(body.path("totalCount").asInt(0), data, attempt);
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
