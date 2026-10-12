package kr.govbenefit.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 서명 키 규칙(결정 0014). 키가 없으면 local·테스트에서만 임의 키를 만들고, 그 밖에서는 기동에 실패한다.
 */
class JwtSecretTest {

    @Test
    void 키가_없으면_기동에_실패한다() {
        assertThatThrownBy(() -> JwtConfig.secretBytes(properties(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> JwtConfig.secretBytes(properties("  ", false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 허용하면_키가_없을_때_32바이트_임의_키를_만든다() {
        byte[] first = JwtConfig.secretBytes(properties(null, true));
        byte[] second = JwtConfig.secretBytes(properties("", true));

        assertThat(first).hasSize(32);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 키는_base64로_32바이트_이상이어야_한다() {
        byte[] key = new byte[32];
        key[0] = 7;

        assertThat(JwtConfig.secretBytes(properties(Base64.getEncoder().encodeToString(key), false))).isEqualTo(key);
        assertThatThrownBy(() -> JwtConfig.secretBytes(properties(Base64.getEncoder().encodeToString(new byte[31]), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
        assertThatThrownBy(() -> JwtConfig.secretBytes(properties("not base64!", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void 키가_있으면_임의_키를_만들지_않는다() {
        byte[] key = new byte[48];
        key[47] = 1;

        assertThat(JwtConfig.secretBytes(properties(Base64.getEncoder().encodeToString(key), true))).isEqualTo(key);
    }

    @Test
    void 설정을_문자열로_바꿔도_키가_드러나지_않는다() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(properties(secret, false).toString()).doesNotContain(secret).contains("****");
    }

    private static JwtProperties properties(String secret, boolean generateSecretIfMissing) {
        return new JwtProperties(secret, "gov-benefit-helper", Duration.ofMinutes(15), generateSecretIfMissing);
    }
}
