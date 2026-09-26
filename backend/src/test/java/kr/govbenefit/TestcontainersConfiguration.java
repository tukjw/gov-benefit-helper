package kr.govbenefit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * 문자셋과 정렬 규칙은 docker-compose.local.yml과 같게 둔다.
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
                .withUrlParam("serverTimezone", "Asia/Seoul");
    }

}
