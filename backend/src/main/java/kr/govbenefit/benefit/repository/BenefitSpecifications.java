package kr.govbenefit.benefit.repository;

import java.util.List;
import kr.govbenefit.benefit.domain.Benefit;
import org.springframework.data.jpa.domain.Specification;

/**
 * 혜택 검색 조건. 조건 값이 비어 있으면 제한하지 않는다.
 */
public final class BenefitSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private BenefitSpecifications() {
    }

    /** 이름에 단어를 모두 포함한다. 단어 안의 LIKE 특수 문자는 글자 그대로 찾는다. */
    public static Specification<Benefit> nameContainsAll(List<String> words) {
        return Specification.allOf(words.stream().map(BenefitSpecifications::nameContains).toList());
    }

    public static Specification<Benefit> serviceFieldIn(List<String> values) {
        return in("serviceField", values);
    }

    public static Specification<Benefit> agencyTypeIn(List<String> values) {
        return in("agencyType", values);
    }

    static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Specification<Benefit> nameContains(String word) {
        String pattern = "%" + escapeLike(word) + "%";
        return (root, query, cb) -> cb.like(root.get("name"), pattern, LIKE_ESCAPE);
    }

    private static Specification<Benefit> in(String attribute, List<String> values) {
        if (values.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> root.get(attribute).in(values);
    }
}
