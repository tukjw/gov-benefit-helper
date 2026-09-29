package kr.govbenefit;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * #14 머지 보호 확인용. 이 테스트가 든 PR은 머지하지 않고 닫는다.
 */
class MergeGuardVerificationTest {

    @Test
    void failsOnPurpose() {
        fail("머지 보호 확인용 실패 테스트");
    }

}
