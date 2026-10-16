package kr.govbenefit.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerificationCodesTest {

    @Test
    void 인증번호는_6자리_숫자다() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = VerificationCodes.generate();
            assertThat(code).matches("\\d{6}");
            codes.add(code);
        }
        assertThat(codes).hasSizeGreaterThan(1);
    }

    @Test
    void 앞자리_0도_자리로_센다() {
        assertThat(VerificationCodes.format(7)).isEqualTo("000007");
        assertThat(VerificationCodes.format(0)).isEqualTo("000000");
        assertThat(VerificationCodes.format(999_999)).isEqualTo("999999");
    }

    @Test
    void 해시는_SHA_256_소문자_hex이고_같은_번호만_맞는다() {
        String hash = VerificationCodes.hash("042917");

        // echo -n 042917 | shasum -a 256
        assertThat(hash).isEqualTo("39f441d2e565b4ec059c26fbebebebb494fb41fc99f1b21567ae2e5b54a21823");
        assertThat(VerificationCodes.matches("042917", hash)).isTrue();
        assertThat(VerificationCodes.matches("042918", hash)).isFalse();
        assertThat(VerificationCodes.matches("42917", hash)).isFalse();
    }
}
