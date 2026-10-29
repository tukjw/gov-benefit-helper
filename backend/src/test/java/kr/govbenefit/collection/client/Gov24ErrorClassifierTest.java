package kr.govbenefit.collection.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import kr.govbenefit.collection.domain.CollectionErrorType;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class Gov24ErrorClassifierTest {

    @Test
    void 상태_코드로_인증_실패_호출_제한_일시_장애_잘못된_응답을_나눈다() {
        assertThat(Gov24ErrorClassifier.ofStatus(401)).isEqualTo(CollectionErrorType.API_AUTH);
        assertThat(Gov24ErrorClassifier.ofStatus(403)).isEqualTo(CollectionErrorType.API_AUTH);
        assertThat(Gov24ErrorClassifier.ofStatus(429)).isEqualTo(CollectionErrorType.API_RATE_LIMIT);
        assertThat(Gov24ErrorClassifier.ofStatus(500)).isEqualTo(CollectionErrorType.API_UNAVAILABLE);
        assertThat(Gov24ErrorClassifier.ofStatus(503)).isEqualTo(CollectionErrorType.API_UNAVAILABLE);
        assertThat(Gov24ErrorClassifier.ofStatus(400)).isEqualTo(CollectionErrorType.API_BAD_RESPONSE);
        assertThat(Gov24ErrorClassifier.ofStatus(404)).isEqualTo(CollectionErrorType.API_BAD_RESPONSE);
    }

    @Test
    void 응답을_받지_못한_실패는_원인으로_시간_초과와_연결_실패를_나눈다() {
        assertThat(Gov24ErrorClassifier.ofIoFailure(accessFailure(new HttpTimeoutException("request timed out"))))
                .isEqualTo(CollectionErrorType.API_TIMEOUT);
        assertThat(Gov24ErrorClassifier.ofIoFailure(accessFailure(new HttpConnectTimeoutException("connect"))))
                .isEqualTo(CollectionErrorType.API_TIMEOUT);
        assertThat(Gov24ErrorClassifier.ofIoFailure(accessFailure(new ConnectException("refused"))))
                .isEqualTo(CollectionErrorType.API_UNAVAILABLE);
    }

    @Test
    void 재시도와_실행_중단은_유형으로_정해진다() {
        assertThat(CollectionErrorType.values()).filteredOn(CollectionErrorType::retryable)
                .containsExactlyInAnyOrder(CollectionErrorType.API_TIMEOUT, CollectionErrorType.API_UNAVAILABLE);
        assertThat(CollectionErrorType.values()).filteredOn(CollectionErrorType::abortsRun)
                .containsExactlyInAnyOrder(CollectionErrorType.API_AUTH, CollectionErrorType.API_RATE_LIMIT,
                        CollectionErrorType.BUDGET_EXCEEDED, CollectionErrorType.INTERNAL);
    }

    private static ResourceAccessException accessFailure(IOException cause) {
        return new ResourceAccessException("I/O error", cause);
    }
}
