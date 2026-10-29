package kr.govbenefit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.lifecycle.Startable;

/**
 * 모든 테스트가 함께 쓰는 MySQL 컨테이너와 테이블 정리(결정 0033).
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class SharedTestDatabaseTest {

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 컨테이너는_bean이_아니라_컨텍스트를_닫아도_닫히지_않는다() {
        // bean이면 컨텍스트를 닫을 때 Spring Boot가 컨테이너를 close()한다.
        assertThat(applicationContext.getBeansOfType(Startable.class)).isEmpty();
    }

    @Test
    void 테스트마다_시드를_뺀_테이블이_비어_있다() {
        assertThat(DatabaseCleanupListener.tables(jdbcTemplate)).contains("user_account", "benefit", "collection_run")
                .doesNotContainAnyElementsOf(DatabaseCleanupListener.KEEP_TABLES)
                .allSatisfy(table -> assertThat(rows(table)).as(table).isZero());
        assertThat(rows("region")).isPositive();
    }

    private int rows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
