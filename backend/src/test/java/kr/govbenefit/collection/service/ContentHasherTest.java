package kr.govbenefit.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContentHasherTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ContentHasher hasher = new ContentHasher(jsonMapper);

    @Test
    void 키_순서가_달라도_같은_해시다() {
        JsonNode a = jsonMapper.readTree("{\"서비스ID\":\"1\",\"서비스명\":\"가\",\"선정기준\":null}");
        JsonNode b = jsonMapper.readTree("{\"선정기준\":null,\"서비스명\":\"가\",\"서비스ID\":\"1\"}");

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b)).hasSize(64);
    }

    @Test
    void 조회수는_해시에서_뺀다() {
        JsonNode before = jsonMapper.readTree("{\"서비스ID\":\"1\",\"조회수\":10}");
        JsonNode after = jsonMapper.readTree("{\"서비스ID\":\"1\",\"조회수\":11}");

        assertThat(hasher.hash(before)).isEqualTo(hasher.hash(after));
    }

    @Test
    void 내용이_바뀌면_해시가_바뀐다() {
        JsonNode before = jsonMapper.readTree("{\"서비스ID\":\"1\",\"지원내용\":\"가\"}");
        JsonNode after = jsonMapper.readTree("{\"서비스ID\":\"1\",\"지원내용\":\"나\"}");
        JsonNode nulled = jsonMapper.readTree("{\"서비스ID\":\"1\",\"지원내용\":null}");

        assertThat(hasher.hash(before)).isNotEqualTo(hasher.hash(after)).isNotEqualTo(hasher.hash(nulled));
    }
}
