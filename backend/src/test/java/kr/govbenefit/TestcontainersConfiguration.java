package kr.govbenefit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 테스트 컨텍스트가 MySQL 컨테이너 하나를 함께 쓴다(결정 0032).
 * 컨테이너를 bean으로 두면 컨텍스트가 닫힐 때 함께 닫히므로, static으로 한 번 띄우고 접속 정보만 bean으로 넘긴다.
 * 컨테이너는 테스트 JVM이 끝날 때 Testcontainers가 지운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * 문자셋, 정렬 규칙, 시간대는 docker-compose.local.yml과 같게 둔다.
     * serverTimezone은 서버 세션 시간대를 바꾸지 않고 KST라고 가정만 하므로, 서버도 KST로 띄워야 시각이 어긋나지 않는다.
     * DB 이름을 로컬·운영과 다르게 두어 엔티티가 스키마 이름을 고정하지 않는지 함께 확인한다.
     */
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("gov_benefit_test")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci")
            .withEnv("TZ", "Asia/Seoul")
            .withUrlParam("serverTimezone", "Asia/Seoul");

    static {
        MYSQL.start();
    }

    @Bean
    DynamicPropertyRegistrar mysqlProperties() {
        return registry -> {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL::getUsername);
            registry.add("spring.datasource.password", MYSQL::getPassword);
        };
    }

}
