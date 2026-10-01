package kr.govbenefit.collection.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import kr.govbenefit.collection.domain.CollectionErrorType;

/**
 * 공공 API 실패를 유형으로 나눈다(결정 0006). 호출 제한 초과 응답은 관찰하지 못해 HTTP 429로 가정했으므로,
 * 실제 응답이 확인되면 여기만 고친다.
 */
final class Gov24ErrorClassifier {

    private Gov24ErrorClassifier() {
    }

    static CollectionErrorType ofStatus(int status) {
        if (status == 401 || status == 403) {
            return CollectionErrorType.API_AUTH;
        }
        if (status == 429) {
            return CollectionErrorType.API_RATE_LIMIT;
        }
        if (status >= 500) {
            return CollectionErrorType.API_UNAVAILABLE;
        }
        return CollectionErrorType.API_BAD_RESPONSE;
    }

    /**
     * 응답을 받지 못한 실패. 원인 중에 시간 초과가 있으면 시간 초과, 아니면 연결 실패로 본다.
     */
    static CollectionErrorType ofIoFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return CollectionErrorType.API_TIMEOUT;
            }
        }
        return CollectionErrorType.API_UNAVAILABLE;
    }
}
