package kr.govbenefit.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RefreshTokenValuesTest {

    @Test
    void 원문은_43자_base64url이고_매번_다르다() {
        String first = RefreshTokenValues.generate();
        String second = RefreshTokenValues.generate();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(RefreshTokenValues.isWellFormed(first)).isTrue();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 형식이_틀린_값을_구분한다() {
        String valid = RefreshTokenValues.generate();

        assertThat(RefreshTokenValues.isWellFormed(null)).isFalse();
        assertThat(RefreshTokenValues.isWellFormed("")).isFalse();
        assertThat(RefreshTokenValues.isWellFormed(valid.substring(1))).isFalse();
        assertThat(RefreshTokenValues.isWellFormed(valid + "A")).isFalse();
        assertThat(RefreshTokenValues.isWellFormed("+" + valid.substring(1))).isFalse();
        assertThat(RefreshTokenValues.isWellFormed("/" + valid.substring(1))).isFalse();
        assertThat(RefreshTokenValues.isWellFormed(valid.substring(1) + "=")).isFalse();
    }

    @Test
    void 해시는_원문의_SHA_256_소문자_hex다() throws Exception {
        String value = RefreshTokenValues.generate();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));

        assertThat(RefreshTokenValues.hash(value)).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(RefreshTokenValues.hash(value)).isEqualTo(RefreshTokenValues.hash(value));
    }
}
