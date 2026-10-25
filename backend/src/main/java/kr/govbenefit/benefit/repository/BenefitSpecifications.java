package kr.govbenefit.benefit.repository;

import java.util.List;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.region.domain.AgencyCategory;
import kr.govbenefit.region.domain.AgencyFilter;
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

    /**
     * 운영 기관 지역(결정 0025). 유형과 코드를 함께 비교해 조건 비교의 운영 기관 지역 분류와 어긋나지 않게 한다.
     *
     * @param filter 없으면 제한하지 않는다.
     */
    public static Specification<Benefit> agencyRegionIn(AgencyFilter filter) {
        if (filter == null) {
            return Specification.unrestricted();
        }
        return Specification.anyOf(
                agencyIn(AgencyCategory.LOCAL_GOVERNMENT, filter.localGovernmentCodes()),
                agencyIn(AgencyCategory.EDUCATION_OFFICE, filter.educationOfficeCodes()));
    }

    static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Specification<Benefit> nameContains(String word) {
        String pattern = "%" + escapeLike(word) + "%";
        return (root, query, cb) -> cb.like(root.get("name"), pattern, LIKE_ESCAPE);
    }

    private static Specification<Benefit> agencyIn(AgencyCategory category, List<String> codes) {
        if (codes.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> cb.and(root.get("agencyType").in(category.agencyTypes()),
                root.get("agencyCode").in(codes));
    }

    private static Specification<Benefit> in(String attribute, List<String> values) {
        if (values.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> root.get(attribute).in(values);
    }
}
