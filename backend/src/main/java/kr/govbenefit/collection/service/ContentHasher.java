package kr.govbenefit.collection.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원문 레코드의 내용 해시. 조회수처럼 내용이 아닌 필드는 빼고, 키 순서와 무관하게 같은 값을 낸다.
 */
@Component
public class ContentHasher {

    /** 내용 변경 비교에서 뺄 필드. 목록의 조회수는 수집할 때마다 바뀐다. */
    static final Set<String> NON_CONTENT_FIELDS = Set.of("조회수");

    private final JsonMapper jsonMapper;

    public ContentHasher(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 평평한 JSON 객체 레코드의 SHA-256(16진수 64자).
     */
    public String hash(JsonNode record) {
        Map<String, JsonNode> content = new TreeMap<>();
        record.properties().forEach(field -> {
            if (!NON_CONTENT_FIELDS.contains(field.getKey())) {
                content.put(field.getKey(), field.getValue());
            }
        });
        return sha256(jsonMapper.writeValueAsBytes(content));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
