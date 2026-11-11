import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * 실제 SES 사전 측정(#137). 실제 SES로 메일박스 시뮬레이터(success@simulator.amazonses.com)에만 보낸다.
 * 시뮬레이터는 초당 한도에는 잡히고, 하루 할당량·평판 지표에는 잡히지 않는다. 실행은 ses-probe.sh.
 *
 * 기본 모드
 *   1) 지연: 앱과 같은 클라이언트 설정으로 LATENCY_COUNT건(100)을 초당 LATENCY_RATE건(5)으로 차례로 보낸다.
 *   2) 몰아 보내기: BURST_COUNT건(60)을 BURST_THREADS개(12) 스레드로 한꺼번에 보낸다. 0이면 하지 않는다.
 *   결과: PROBE_OUT/results.jsonl, PROBE_OUT/summary.txt
 * MODE=sustained: SUSTAIN_RATE건/초(30)로 SUSTAIN_SECONDS초(20) 동안 보내 속도 초과 응답을 남긴다.
 *   결과: PROBE_OUT/sustained.jsonl
 *
 * 환경 변수: SES_ACCESS_KEY_ID, SES_SECRET_ACCESS_KEY, SES_REGION, SES_FROM(앱의 app.env와 같은 이름), PROBE_OUT(결과 디렉터리),
 * SES_ENDPOINT(선택, 가짜 SES로 미리 돌려 볼 때만)
 */
public class SesRealProbe {

    static final String TO = "success+p137-%s-%d@simulator.amazonses.com";

    record Result(String phase, int index, long startEpochMs, long latencyMicros, int status, String errorCode,
                  String errorMessage, String clientError) {
        String json() {
            return "{\"phase\":\"%s\",\"index\":%d,\"start\":%d,\"latencyMs\":%.3f,\"status\":%d,\"errorCode\":%s,\"message\":%s,\"clientError\":%s}"
                    .formatted(phase, index, startEpochMs, latencyMicros / 1000.0, status, q(errorCode),
                            q(errorMessage), q(clientError));
        }

        static String q(String s) {
            return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    public static void main(String[] args) throws Exception {
        int latencyCount = Integer.parseInt(System.getenv().getOrDefault("LATENCY_COUNT", "100"));
        int latencyRate = Integer.parseInt(System.getenv().getOrDefault("LATENCY_RATE", "5"));
        int burstCount = Integer.parseInt(System.getenv().getOrDefault("BURST_COUNT", "60"));
        int burstThreads = Integer.parseInt(System.getenv().getOrDefault("BURST_THREADS", "12"));
        Path out = Path.of(System.getenv("PROBE_OUT"));
        Files.createDirectories(out);

        // 앱(SesAccountMailSender)과 같은 설정: URLConnection 클라이언트, 연결 5초, 소켓 10초, SDK 재시도 없음
        var builder = SesV2Client.builder()
                .region(Region.of(req("SES_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(req("SES_ACCESS_KEY_ID"), req("SES_SECRET_ACCESS_KEY"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
        // 가짜 SES로 미리 돌려 볼 때만 쓴다. 비우면 실제 리전 엔드포인트다.
        String endpoint = System.getenv("SES_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(java.net.URI.create(endpoint));
        }
        SesV2Client client = builder.build();
        String from = req("SES_FROM");
        String stamp = String.valueOf(Instant.now().getEpochSecond());

        List<Result> results = Collections.synchronizedList(new ArrayList<>());
        if ("sustained".equals(System.getenv("MODE"))) {
            sustained(client, from, stamp, results, out);
            return;
        }
        long interval = 1_000_000_000L / latencyRate;
        long next = System.nanoTime();
        for (int i = 0; i < latencyCount; i++) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(Duration.ofNanos(wait));
            }
            next = System.nanoTime() + interval;
            results.add(send(client, from, "latency", stamp, i));
        }

        if (burstCount > 0) {
            Thread.sleep(2000);
            ExecutorService pool = Executors.newFixedThreadPool(burstThreads);
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < burstCount; i++) {
                int index = i;
                futures.add(pool.submit(() -> send(client, from, "burst", stamp, index)));
            }
            for (Future<Result> f : futures) {
                results.add(f.get());
            }
            pool.shutdown();
        }
        client.close();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("results.jsonl")))) {
            results.forEach(r -> w.println(r.json()));
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.txt")))) {
            summarize(results, w);
        }
        summarize(results, new PrintWriter(System.out, true));
    }

    /**
     * SUSTAIN_RATE건/초로 SUSTAIN_SECONDS초 동안 일정 간격으로 보낸다(응답을 기다리지 않고 시각마다 시작).
     * 속도 초과와 연결 오류가 아닌 실패(계정 발송 중지 등)가 오면 바로 멈춘다.
     */
    static void sustained(SesV2Client client, String from, String stamp, List<Result> results, Path out)
            throws Exception {
        int rate = Integer.parseInt(System.getenv().getOrDefault("SUSTAIN_RATE", "30"));
        int seconds = Integer.parseInt(System.getenv().getOrDefault("SUSTAIN_SECONDS", "20"));
        int total = rate * seconds;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(8, rate));
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<Future<Result>> futures = new ArrayList<>();
        long begin = System.nanoTime();
        long interval = 1_000_000_000L / rate;
        for (int i = 0; i < total && !stop.get(); i++) {
            long wait = begin + i * interval - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(Duration.ofNanos(wait));
            }
            int index = i;
            futures.add(pool.submit(() -> {
                Result r = send(client, from, "sustained", stamp, index);
                boolean throttleLike = r.status == 400 && r.errorCode != null
                        && (r.errorCode.contains("Throttl") || String.valueOf(r.errorMessage).contains("rate"));
                if (r.status != 200 && r.status != 429 && r.status != -1 && !throttleLike && r.status < 500) {
                    stop.set(true);
                }
                return r;
            }));
        }
        for (Future<Result> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        client.close();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("sustained.jsonl")))) {
            results.forEach(r -> w.println(r.json()));
        }
        PrintWriter w = new PrintWriter(System.out, true);
        if (stop.get()) {
            w.println("!! 예상 밖의 오류로 중간에 멈췄다");
        }
        long t0 = results.stream().mapToLong(Result::startEpochMs).min().orElse(0);
        java.util.Map<Long, List<Result>> bySecond = new java.util.TreeMap<>(results.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> (r.startEpochMs - t0) / 1000)));
        w.println("초  보냄  성공  실패");
        bySecond.forEach((s, rs) -> w.printf("%2d  %4d  %4d  %4d%n", s, rs.size(),
                rs.stream().filter(r -> r.status == 200).count(), rs.stream().filter(r -> r.status != 200).count()));
        Result firstFail = results.stream().filter(r -> r.status != 200)
                .min(java.util.Comparator.comparingLong(Result::startEpochMs)).orElse(null);
        if (firstFail != null) {
            w.printf("첫 실패: 시작 %.2f초, 그때까지 성공 %d건%n", (firstFail.startEpochMs - t0) / 1000.0,
                    results.stream().filter(r -> r.status == 200 && r.startEpochMs < firstFail.startEpochMs).count());
        }
        summarize(results.stream().map(r -> new Result("burst", r.index, r.startEpochMs, r.latencyMicros, r.status,
                r.errorCode, r.errorMessage, r.clientError)).toList(), w);
    }

    static Result send(SesV2Client client, String from, String phase, String stamp, int index) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(TO.formatted(stamp + phase, index)))
                .content(c -> c.simple(m -> m
                        .subject(s -> s.data("[측정] SES 지연·속도 초과 확인(#137)").charset("UTF-8"))
                        .body(b -> b.text(t -> t.data("메일박스 시뮬레이터로 보내는 측정용 메일입니다.").charset("UTF-8")))))
                .build();
        long startMs = System.currentTimeMillis();
        long t = System.nanoTime();
        try {
            client.sendEmail(request);
            return new Result(phase, index, startMs, (System.nanoTime() - t) / 1000, 200, null, null, null);
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            String msg = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorMessage();
            return new Result(phase, index, startMs, (System.nanoTime() - t) / 1000, e.statusCode(), code, msg, null);
        } catch (SdkClientException e) {
            return new Result(phase, index, startMs, (System.nanoTime() - t) / 1000, -1, null, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void summarize(List<Result> results, PrintWriter w) {
        for (String phase : List.of("latency", "burst")) {
            List<Result> rs = results.stream().filter(r -> r.phase.equals(phase)).toList();
            if (rs.isEmpty()) {
                continue;
            }
            List<Long> ok = rs.stream().filter(r -> r.status == 200).map(Result::latencyMicros).sorted().toList();
            w.printf("[%s] 요청 %d, 성공 %d%n", phase, rs.size(), ok.size());
            if (!ok.isEmpty()) {
                w.printf("  성공 지연(ms): 첫 요청 %.1f, min %.1f, p50 %.1f, p90 %.1f, p99 %.1f, max %.1f%n",
                        rs.getFirst().latencyMicros / 1000.0, ok.getFirst() / 1000.0, pct(ok, 50), pct(ok, 90),
                        pct(ok, 99), ok.getLast() / 1000.0);
            }
            rs.stream().filter(r -> r.status != 200)
                    .collect(java.util.stream.Collectors.groupingBy(
                            r -> r.status + " " + r.errorCode + " \"" + r.errorMessage + "\" " + r.clientError,
                            java.util.stream.Collectors.counting()))
                    .forEach((k, v) -> w.printf("  실패 %d건: %s → 지금 앱 분류 retryable=%s%n", v, k,
                            k.startsWith("-1") || k.startsWith("429") || k.matches("^5\\d\\d.*")));
        }
        w.flush();
    }

    static double pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1)) / 1000.0;
    }

    static String req(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " 환경 변수가 없습니다.");
        }
        return v;
    }
}
