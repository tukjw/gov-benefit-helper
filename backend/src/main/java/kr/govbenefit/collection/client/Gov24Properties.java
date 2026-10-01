package kr.govbenefit.collection.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공서비스(혜택) API 설정. 인증키는 실행 환경의 GOV24_SERVICE_KEY로만 받는다.
 *
 * @param connectTimeout 연결 시간 제한
 * @param readTimeout    요청을 보낸 뒤 응답 헤더를 받을 때까지의 시간 제한
 * @param maxRetries     재시도 가능한 실패에서 첫 시도 뒤 다시 시도하는 최대 횟수
 * @param retryDelay     첫 재시도 전 대기. 이후 두 배씩 늘린다
 * @param maxCallsPerRun 수집 실행 한 번에 보낼 수 있는 HTTP 요청 수(재시도 포함)
 */
@ConfigurationProperties("gov24")
public record Gov24Properties(URI baseUrl, String serviceKey, int perPage, Duration connectTimeout,
                              Duration readTimeout, int maxRetries, Duration retryDelay, int maxCallsPerRun) {

    public String requireServiceKey() {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("공공서비스 API 인증키(GOV24_SERVICE_KEY)가 설정되지 않았습니다.");
        }
        return serviceKey;
    }

    /**
     * 설정을 로그로 남겨도 인증키가 드러나지 않게 한다.
     */
    @Override
    public String toString() {
        return "Gov24Properties[baseUrl=" + baseUrl + ", serviceKey=" + (serviceKey == null || serviceKey.isBlank()
                ? "(없음)" : "****") + ", perPage=" + perPage + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxRetries=" + maxRetries + ", retryDelay=" + retryDelay
                + ", maxCallsPerRun=" + maxCallsPerRun + "]";
    }
}
