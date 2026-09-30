package kr.govbenefit.collection.client;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공서비스(혜택) API 설정. 인증키는 실행 환경의 GOV24_SERVICE_KEY로만 받는다.
 */
@ConfigurationProperties("gov24")
public record Gov24Properties(URI baseUrl, String serviceKey, int perPage) {

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
                ? "(없음)" : "****") + ", perPage=" + perPage + "]";
    }
}
