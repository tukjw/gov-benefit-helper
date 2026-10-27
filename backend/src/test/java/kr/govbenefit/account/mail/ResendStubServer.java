package kr.govbenefit.account.mail;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resend {@code POST /emails}를 흉내 내는 로컬 HTTP 서버(결정 0032). 받은 요청을 기록하고, 응답을 오류·연결 끊김으로
 * 바꾸거나 테스트가 풀어 줄 때까지 붙잡아 둔다.
 */
public class ResendStubServer implements Closeable {

    private static final Duration HOLD_LIMIT = Duration.ofSeconds(10);

    /**
     * @param body JSON 본문
     */
    public record ReceivedRequest(String method, String path, String authorization, String userAgent,
                                  JsonNode body) {

        public String to() {
            return body.get("to").get(0).asString();
        }

        public String text() {
            return body.get("text").asString();
        }

        /** 가입 인증번호 메일 본문의 번호 */
        public String signupCode() {
            return find(SIGNUP_CODE);
        }

        /** 재설정 메일 본문의 링크 */
        public String resetUrl() {
            return find(RESET_URL);
        }

        /** 재설정 링크의 {@code #token=} 뒤의 값 */
        public String resetToken() {
            String url = resetUrl();
            return url.substring(url.indexOf("#token=") + "#token=".length());
        }

        private String find(Pattern pattern) {
            Matcher matcher = pattern.matcher(text());
            if (!matcher.find()) {
                throw new AssertionError(pattern + "가 본문에 없습니다: " + text());
            }
            return matcher.group(1);
        }
    }

    private static final Pattern SIGNUP_CODE = Pattern.compile("인증번호: (\\d{6})");
    private static final Pattern RESET_URL = Pattern.compile("(https?://\\S+#token=\\S+)");

    private final MockWebServer server = new MockWebServer();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private volatile MockResponse response;
    private volatile CountDownLatch hold;
    private volatile CountDownLatch arrival;
    private final AtomicInteger held = new AtomicInteger();

    public static ResendStubServer start() {
        ResendStubServer stub = new ResendStubServer();
        stub.server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
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

    /** 실제 설정({@code https://api.resend.com})처럼 끝에 {@code /}가 없다. */
    public String baseUrl() {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    /**
     * 기본 응답(200)으로 되돌리고 받은 요청 기록을 지운다. 붙잡힌 요청이 있으면 풀어 준다.
     */
    public void reset() {
        release();
        received.clear();
        response = response(200, "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}");
        hold = null;
        arrival = new CountDownLatch(1);
    }

    /**
     * 다음 요청부터 이 상태 코드와 본문으로 응답한다.
     */
    public void respondWith(int status, String body) {
        response = response(status, body);
    }

    /**
     * 다음 요청부터 응답하기 전에 연결을 끊는다.
     */
    public void disconnect() {
        response = new MockResponse.Builder().onResponseStart(new SocketEffect.CloseSocket()).build();
    }

    /**
     * 다음 요청부터 {@link #release()}할 때까지(최대 10초) 응답하지 않는다. 요청은 도착하는 즉시 기록한다.
     */
    public void hold() {
        hold = new CountDownLatch(1);
    }

    public void release() {
        CountDownLatch latch = hold;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 요청이 하나라도 도착할 때까지 기다린다.
     *
     * @return 시간 안에 도착했으면 true
     */
    public boolean awaitRequest(Duration timeout) throws InterruptedException {
        return arrival.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * {@link #hold()}로 붙잡혀 아직 응답하지 않은 요청 수
     */
    public int held() {
        return held.get();
    }

    public List<ReceivedRequest> received() {
        return List.copyOf(received);
    }

    public ReceivedRequest last() {
        if (received.isEmpty()) {
            throw new AssertionError("Resend stub이 받은 요청이 없습니다.");
        }
        return received.getLast();
    }

    @Override
    public void close() {
        release();
        server.close();
    }

    private MockResponse respond(RecordedRequest request) throws InterruptedException {
        String body = request.getBody() == null ? "" : request.getBody().utf8();
        received.add(new ReceivedRequest(request.getMethod(), request.getUrl().encodedPath(),
                request.getHeaders().get("Authorization"), request.getHeaders().get("User-Agent"),
                body.isEmpty() ? null : jsonMapper.readTree(body)));
        CountDownLatch latch = hold;
        if (latch == null) {
            arrival.countDown();
            return response;
        }
        // 도착을 알리기 전에 세어, 도착을 확인한 쪽이 붙잡힌 요청을 바로 볼 수 있게 한다.
        held.incrementAndGet();
        arrival.countDown();
        try {
            latch.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            held.decrementAndGet();
        }
        return response;
    }

    private static MockResponse response(int status, String body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
