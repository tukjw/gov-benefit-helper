package kr.govbenefit.collection.config;

import java.time.Clock;
import java.time.ZoneId;
import kr.govbenefit.collection.client.Gov24Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Gov24Properties.class)
@EnableScheduling
public class CollectionConfig {

    /** 시각은 DB·애플리케이션 시간대(Asia/Seoul, 결정 0002) 기준이다. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
