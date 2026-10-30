package kr.govbenefit.common;

import jakarta.servlet.Filter;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 요청마다 {@code request.getRemoteAddr()}를 기록한다. {@link ClientIpTest}가 쓴다.
 * 실제 서버를 띄우는 테스트들이 컨텍스트를 함께 쓰도록 테스트 클래스 밖에 둔다. 테스트 클래스 안의 설정은 그 클래스의 컨텍스트에만 더해진다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RemoteAddrRecorder {

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
