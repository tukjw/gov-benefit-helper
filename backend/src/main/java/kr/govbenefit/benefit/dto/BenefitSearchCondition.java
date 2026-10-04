package kr.govbenefit.benefit.dto;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import kr.govbenefit.common.api.InvalidInputException;

/**
 * 혜택 검색 조건(DEC-SEARCH). 키워드 단어는 모두 이름에 있어야 하고, 한 필터 안의 값은 OR, 필터 사이와 키워드는 AND다.
 *
 * @param keywords    공백으로 나눈 키워드 단어. 비어 있으면 키워드 조건이 없다.
 * @param fields      분야 원문 값. 비어 있으면 분야 조건이 없다.
 * @param agencyTypes 운영 기관 유형 원문 값. 비어 있으면 기관 유형 조건이 없다.
 */
public record BenefitSearchCondition(List<String> keywords, List<String> fields, List<String> agencyTypes) {

    public static final int MAX_KEYWORD_LENGTH = 100;
    public static final int MAX_KEYWORDS = 10;

    /** 전각 공백(U+3000)을 포함한 유니코드 공백 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public static BenefitSearchCondition of(String keyword, List<String> fields, List<String> agencyTypes) {
        List<String> keywords = keywords(keyword);
        if (keywords.size() > MAX_KEYWORDS) {
            throw new InvalidInputException("keyword", keyword, "단어는 " + MAX_KEYWORDS + "개까지 입력할 수 있습니다.");
        }
        return new BenefitSearchCondition(keywords, values(fields), values(agencyTypes));
    }

    private static List<String> keywords(String keyword) {
        if (keyword == null) {
            return List.of();
        }
        return Arrays.stream(WHITESPACE.split(keyword))
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
    }

    /** 필터 값은 원문과 비교하므로 바꾸지 않고, 빈 값만 뺀다. */
    private static List<String> values(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
