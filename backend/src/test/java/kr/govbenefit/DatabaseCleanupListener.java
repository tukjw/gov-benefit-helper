package kr.govbenefit;

import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 테스트 메서드마다 시드를 뺀 모든 테이블을 비운다(결정 0032). 모든 테스트가 DB 하나를 함께 쓰므로 다른 테스트가 남긴 행을 지운다.
 * META-INF/spring.factories로 모든 Spring 테스트에 등록되고, 테스트 클래스의 {@code @BeforeEach}보다 먼저 돈다.
 */
public class DatabaseCleanupListener extends AbstractTestExecutionListener {

    /** 마이그레이션이 만든 이력과 시드. 마이그레이션으로 데이터를 넣는 테이블을 더하면 여기에 더한다. */
    static final Set<String> KEEP_TABLES = Set.of("flyway_schema_history", "region");

    // 모든 컨텍스트가 같은 DB를 쓰므로 한 번만 읽는다.
    private static volatile List<String> tables;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        JdbcTemplate jdbcTemplate = testContext.getApplicationContext()
                .getBeanProvider(JdbcTemplate.class).getIfAvailable();
        if (jdbcTemplate == null) {
            return;
        }
        List<String> targets = tables(jdbcTemplate);
        // FK 검사는 세션 변수라 같은 커넥션에서 끄고, 풀로 돌려주기 전에 다시 켠다.
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String table : targets) {
                        statement.executeUpdate("DELETE FROM " + table);
                    }
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    static List<String> tables(JdbcTemplate jdbcTemplate) {
        if (tables == null) {
            tables = jdbcTemplate.queryForList(
                            "SELECT table_name FROM information_schema.tables"
                                    + " WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                            String.class).stream()
                    .filter(table -> !KEEP_TABLES.contains(table))
                    .sorted()
                    .toList();
        }
        return tables;
    }
}
