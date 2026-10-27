package kr.govbenefit;

import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 고정 시계를 쓰는 테스트가 함께 쓰는 설정(결정 0032). 다른 설정이 같으면 컨텍스트를 공유하므로,
 * 각 테스트는 {@code @BeforeEach}에서 {@link MutableClock#set}으로 자기 기준 시각을 맞춘다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-10-16T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    }
}
