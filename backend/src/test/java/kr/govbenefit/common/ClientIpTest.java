package kr.govbenefit.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import kr.govbenefit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Cloudflare가 넣는 {@code CF-Connecting-IP}가 요청자 주소가 된다(결정 0034). 헤더는 Tomcat이 처리하므로 MockMvc가 아니라
 * 실제 서버를 띄워 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gov24.schedule.enabled=false")
@Import({TestcontainersConfiguration.class, ClientIpTest.RemoteAddrRecorder.class})
class ClientIpTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private AtomicReference<String> recordedRemoteAddr;

    @Test
    void CF_Connecting_IP가_요청자_주소가_된다() throws Exception {
        send(request().header("CF-Connecting-IP", "203.0.113.7"));

        assertThat(recordedRemoteAddr.get()).isEqualTo("203.0.113.7");
    }

    @Test
    void 헤더가_없으면_연결한_주소가_요청자_주소다() throws Exception {
        send(request());

        assertThat(recordedRemoteAddr.get()).isEqualTo("127.0.0.1");
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"));
    }

    private void send(HttpRequest.Builder request) throws Exception {
        HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RemoteAddrRecorder {

        @Bean
        AtomicReference<String> recordedRemoteAddr() {
            return new AtomicReference<>();
        }

        @Bean
        Filter remoteAddrRecordingFilter(AtomicReference<String> recordedRemoteAddr) {
            return (request, response, chain) -> {
                recordedRemoteAddr.set(request.getRemoteAddr());
                chain.doFilter(request, response);
            };
        }
    }
}
