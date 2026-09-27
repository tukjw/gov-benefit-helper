package kr.govbenefit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * 문자셋, 정렬 규칙, 시간대는 docker-compose.local.yml과 같게 둔다.
     * serverTimezone은 서버 세션 시간대를 바꾸지 않고 KST라고 가정만 하므로, 서버도 KST로 띄워야 시각이 어긋나지 않는다.
     * DB 이름을 로컬·운영과 다르게 두어 엔티티가 스키마 이름을 고정하지 않는지 함께 확인한다.
     */
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("gov_benefit_test")
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci")
                .withEnv("TZ", "Asia/Seoul")
                .withUrlParam("serverTimezone", "Asia/Seoul");
    }

}
