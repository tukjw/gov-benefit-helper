package kr.govbenefit.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import kr.govbenefit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * actuator는 관리 포트에서만 열린다(결정 0037). 배포 스크립트의 상태 확인과 Prometheus의 지표는 토큰 없이 읽고,
 * Tunnel이 전달하는 앱 포트에는 actuator 경로가 없다. 포트가 나뉘는 것은 MockMvc로 볼 수 없어 실제 서버를 띄운다.
 * 테스트 컨텍스트마다 공유 MySQL 연결을 잡으므로(결정 0033), 실제 서버를 띄우는 {@link ClientIpTest}와 설정을 같게 두어 컨텍스트를 함께 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gov24.schedule.enabled=false")
@Import({TestcontainersConfiguration.class, RemoteAddrRecorder.class})
class ActuatorEndpointTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int serverPort;
    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void 관리_포트의_상태_확인은_토큰_없이_UP이고_세부_정보가_없다() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    void 관리_포트의_지표는_토큰_없이_요청_JVM_커넥션_풀_지표를_낸다() throws Exception {
        get(serverPort, "/api/profile/options");

        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "http_server_requests_seconds_bucket{",
                "uri=\"/api/profile/options\"",
                "jvm_memory_used_bytes{",
                "jvm_gc_",
                "hikaricp_connections_active{");
    }

    @Test
    void 앱_포트에는_actuator_경로가_없다() throws Exception {
        assertThat(get(serverPort, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void 관리_포트에서도_다른_actuator_경로는_열리지_않는다() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
