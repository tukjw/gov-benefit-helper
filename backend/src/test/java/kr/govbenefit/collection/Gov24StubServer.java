package kr.govbenefit.collection;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.govbenefit.collection.domain.SourceEndpoint;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공공서비스(혜택) API를 흉내 내는 로컬 HTTP 서버.
 * 경로별 레코드 목록을 {@code page}·{@code perPage}로 잘라 실제 응답 형식으로 돌려준다.
 * 레코드는 {@code src/test/resources/gov24/<경로>.json}(T0 스냅샷에서 고른 실제 레코드)에서 읽고, 테스트가 바꿔 시나리오를 만든다.
 */
public class Gov24StubServer implements Closeable {

    public static final String BASE_PATH = "/api/gov24/v3";

    private final MockWebServer server = new MockWebServer();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Map<SourceEndpoint, List<ObjectNode>> records = new EnumMap<>(SourceEndpoint.class);
    private final Map<String, MockResponse> overrides = new ConcurrentHashMap<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    public static Gov24StubServer start() {
        Gov24StubServer stub = new Gov24StubServer();
        stub.server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return stub.respond(request);
            }
        });
        try {
            stub.server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        stub.reset();
        return stub;
    }

    public String baseUrl() {
        return server.url(BASE_PATH).toString();
    }

    /**
     * fixture 레코드로 되돌리고, 바꿔 끼운 응답과 받은 요청 기록을 지운다.
     */
    public synchronized void reset() {
        for (SourceEndpoint endpoint : SourceEndpoint.values()) {
            List<ObjectNode> fixture = new ArrayList<>();
            readFixture(endpoint).forEach(node -> fixture.add((ObjectNode) node));
            records.put(endpoint, fixture);
        }
        overrides.clear();
        authorizations.clear();
    }

    /**
     * 경로의 레코드 목록. 바꾸면 다음 요청부터 반영된다.
     */
    public synchronized List<ObjectNode> records(SourceEndpoint endpoint) {
        return records.get(endpoint);
    }

    public synchronized ObjectNode record(SourceEndpoint endpoint, String serviceId) {
        return records.get(endpoint).stream()
                .filter(record -> serviceId.equals(record.path("서비스ID").asString(null)))
                .findFirst()
                .orElseThrow();
    }

    public synchronized void removeService(String serviceId) {
        records.values().forEach(list -> list.removeIf(
                record -> serviceId.equals(record.path("서비스ID").asString(null))));
    }

    /**
     * 경로의 한 쪽 응답을 바꿔 끼운다.
     */
    public void override(SourceEndpoint endpoint, int page, int status, String body) {
        overrides.put(key(endpoint, page), new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json;charset=UTF-8")
                .body(body)
                .build());
    }

    public List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    @Override
    public void close() {
        server.close();
    }

    private synchronized MockResponse respond(RecordedRequest request) {
        authorizations.add(String.valueOf(request.getHeaders().get("Authorization")));
        String path = request.getUrl().encodedPath();
        SourceEndpoint endpoint = null;
        for (SourceEndpoint candidate : SourceEndpoint.values()) {
            if (path.equals(BASE_PATH + candidate.path())) {
                endpoint = candidate;
            }
        }
        if (endpoint == null) {
            return new MockResponse.Builder().code(404).build();
        }
        // 실제 API처럼 page<1은 1쪽으로 본다.
        int page = Math.max(1, parseInt(request.getUrl().queryParameter("page"), 1));
        int perPage = parseInt(request.getUrl().queryParameter("perPage"), 10);
        MockResponse override = overrides.get(key(endpoint, page));
        if (override != null) {
            return override;
        }
        List<ObjectNode> all = records.get(endpoint);
        int from = Math.min((page - 1) * perPage, all.size());
        int to = Math.min(from + perPage, all.size());
        ArrayNode data = jsonMapper.createArrayNode();
        all.subList(from, to).forEach(data::add);
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("currentCount", data.size());
        body.set("data", data);
        body.put("matchCount", all.size());
        body.put("page", page);
        body.put("perPage", perPage);
        body.put("totalCount", all.size());
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json;charset=UTF-8")
                .body(jsonMapper.writeValueAsString(body))
                .build();
    }

    private JsonNode readFixture(SourceEndpoint endpoint) {
        String name = "gov24" + endpoint.path() + ".json";
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return jsonMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int parseInt(String value, int defaultValue) {
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static String key(SourceEndpoint endpoint, int page) {
        return endpoint + ":" + page;
    }
}
