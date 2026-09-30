package kr.govbenefit.collection.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class Gov24PropertiesTest {

    private static final URI BASE_URL = URI.create("http://localhost/api/gov24/v3");

    @Test
    void 인증키가_없으면_수집할_수_없다() {
        assertThatThrownBy(() -> new Gov24Properties(BASE_URL, "", 1000).requireServiceKey())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Gov24Properties(BASE_URL, null, 1000).requireServiceKey())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 문자열로_바꿔도_인증키가_드러나지_않는다() {
        Gov24Properties properties = new Gov24Properties(BASE_URL, "secret-key-value", 1000);

        assertThat(properties.toString()).doesNotContain("secret-key-value").contains("****");
    }
}
