package kr.govbenefit.collection.domain;

/**
 * 공공서비스(혜택) API의 수집 경로. 수집은 선언 순서(목록 → 상세 → 지원조건)로 한다.
 */
public enum SourceEndpoint {

    SERVICE_LIST("/serviceList"),
    SERVICE_DETAIL("/serviceDetail"),
    SUPPORT_CONDITIONS("/supportConditions");

    private final String path;

    SourceEndpoint(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
