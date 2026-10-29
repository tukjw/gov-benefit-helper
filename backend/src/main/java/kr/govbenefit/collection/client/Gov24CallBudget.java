package kr.govbenefit.collection.client;

import kr.govbenefit.collection.domain.CollectionErrorType;

/**
 * 수집 실행 한 번이 보낼 수 있는 HTTP 요청 수. 재시도를 포함해 시도마다 하나씩 쓴다.
 * 실행 하나가 한 스레드에서만 쓰므로 동기화하지 않는다.
 */
public class Gov24CallBudget {

    private final int limit;
    private int used;

    public Gov24CallBudget(int limit) {
        this.limit = limit;
    }

    /**
     * 요청 하나를 보낼 몫을 가져간다.
     *
     * @throws Gov24ApiException 예산을 모두 썼을 때({@link CollectionErrorType#BUDGET_EXCEEDED})
     */
    public void acquire() {
        if (used >= limit) {
            throw new Gov24ApiException(CollectionErrorType.BUDGET_EXCEEDED,
                    "실행당 호출 예산 " + limit + "회를 모두 썼습니다");
        }
        used++;
    }

    public int used() {
        return used;
    }
}
