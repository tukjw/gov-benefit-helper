package kr.govbenefit.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void 원문은_43자_base64url이고_매번_다르다() {
        String first = OpaqueTokens.generate();
        String second = OpaqueTokens.generate();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(OpaqueTokens.isWellFormed(first)).isTrue();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 형식이_틀린_값을_구분한다() {
        String valid = OpaqueTokens.generate();

        assertThat(OpaqueTokens.isWellFormed(null)).isFalse();
        assertThat(OpaqueTokens.isWellFormed("")).isFalse();
        assertThat(OpaqueTokens.isWellFormed(valid.substring(1))).isFalse();
        assertThat(OpaqueTokens.isWellFormed(valid + "A")).isFalse();
        assertThat(OpaqueTokens.isWellFormed("+" + valid.substring(1))).isFalse();
        assertThat(OpaqueTokens.isWellFormed("/" + valid.substring(1))).isFalse();
        assertThat(OpaqueTokens.isWellFormed(valid.substring(1) + "=")).isFalse();
    }

    @Test
    void 해시는_원문의_SHA_256_소문자_hex다() throws Exception {
        String value = OpaqueTokens.generate();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));

        assertThat(OpaqueTokens.hash(value)).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(OpaqueTokens.hash(value)).isEqualTo(OpaqueTokens.hash(value));
    }
}
