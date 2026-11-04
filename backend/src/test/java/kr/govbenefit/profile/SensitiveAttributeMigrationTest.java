package kr.govbenefit.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.govbenefit.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V15가 동의 기록 없이 저장된 건강 관련 속성을 지우는지(#110). 공용 컨테이너(결정 0033)에 별도 스키마를 만들어 V14까지 올리고,
 * 행을 넣은 뒤 V15를 적용한다. 테스트 계정은 테스트 DB에만 권한이 있어 스키마를 만들 수 있는 root로 접속한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class SensitiveAttributeMigrationTest {

    private static final String SCHEMA = "sensitive_attribute_migration";

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.password}")
    private String password;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // jdbc:mysql://host:port/db?params에서 DB 이름만 바꾼다.
        String schemaUrl = url.replaceFirst("(jdbc:mysql://[^/]+/)[^?]*", "$1" + SCHEMA);
        dataSource = new DriverManagerDataSource(url, "root", password);
        JdbcTemplate server = new JdbcTemplate(dataSource);
        // 앞선 실행이 중간에 멈춰 남긴 스키마를 지운다.
        server.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        server.execute("CREATE DATABASE " + SCHEMA);
        dataSource = new DriverManagerDataSource(schemaUrl, "root", password);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    @Test
    void 동의_기록_없이_저장된_건강_관련_속성을_지우고_다른_속성은_남긴다() {
        migrate("14");
        long mixed = insertProfile("mixed@example.com", "JA0301", "JA0302", "JA0326", "JA0410");
        long sensitiveOnly = insertProfile("sensitive@example.com", "JA0328", "JA0330");
        long other = insertProfile("other@example.com", "JA0322");

        migrate("15");

        assertThat(codes(mixed)).containsExactlyInAnyOrder("JA0326", "JA0410");
        assertThat(codes(sensitiveOnly)).isEmpty();
        assertThat(codes(other)).containsExactly("JA0322");
        assertThat(jdbcTemplate.queryForList("SELECT sensitive_attribute_consented_at FROM user_profile", Object.class))
                .hasSize(3).containsOnlyNulls();
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).target(target).load().migrate();
    }

    private long insertProfile(String email, String... codes) {
        jdbcTemplate.update("INSERT INTO user_account (email, password_hash, created_at) VALUES (?, '{noop}x', NOW(6))",
                email);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, email);
        jdbcTemplate.update("INSERT INTO user_profile (user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))",
                userId);
        for (String code : codes) {
            jdbcTemplate.update("INSERT INTO user_profile_attribute (user_id, condition_code) VALUES (?, ?)", userId,
                    code);
        }
        return userId;
    }

    private List<String> codes(long userId) {
        return jdbcTemplate.queryForList("SELECT condition_code FROM user_profile_attribute WHERE user_id = ?",
                String.class, userId);
    }
}
