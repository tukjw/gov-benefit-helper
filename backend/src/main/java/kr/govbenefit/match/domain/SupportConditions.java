package kr.govbenefit.match.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileAttribute;
import tools.jackson.databind.JsonNode;

/**
 * 혜택 하나의 지원조건 코드 중 비교 항목에 쓰는 값. 원문(supportConditions)에서 조회할 때마다 읽는다(결정 0008).
 * 코드의 의미는 데이터 검증 보고서 §5를 따른다. {@code "Y"}는 그 속성이 있는 사람도 대상에 포함된다는 뜻이다.
 *
 * @param ageStart 대상연령 시작(JA0110). 없거나 정수가 아니면 null이다.
 * @param ageEnd   대상연령 종료(JA0111). 없거나 정수가 아니면 null이다.
 * @param yesCodes 성별·생애·가구 코드 중 값이 {@code "Y"}인 코드
 */
public record SupportConditions(Integer ageStart, Integer ageEnd, Set<String> yesCodes) {

    static final String MALE = "JA0101";
    static final String FEMALE = "JA0102";
    private static final String AGE_START = "JA0110";
    private static final String AGE_END = "JA0111";

    /** 원문에서 읽는 Y·null 코드. 소득과 사업자·기업 대상 코드는 비교하지 않으므로 읽지 않는다. */
    private static final List<String> COMPARED_CODES = Stream.concat(Stream.of(MALE, FEMALE),
                    Stream.of(LifeAttribute.values(), HouseholdAttribute.values())
                            .flatMap(Arrays::stream)
                            .map(ProfileAttribute::jaCode))
            .toList();

    public SupportConditions {
        yesCodes = Set.copyOf(yesCodes);
    }

    /**
     * 문자열 {@code "Y"}만 Y로 본다. 키가 없거나 null이거나 다른 값이면 Y가 아니다.
     */
    public static SupportConditions parse(JsonNode record) {
        Set<String> yesCodes = COMPARED_CODES.stream()
                .filter(code -> {
                    JsonNode value = record.path(code);
                    return value.isString() && "Y".equals(value.stringValue());
                })
                .collect(Collectors.toSet());
        return new SupportConditions(age(record.path(AGE_START)), age(record.path(AGE_END)), yesCodes);
    }

    public boolean isYes(String code) {
        return yesCodes.contains(code);
    }

    /** 값이 Y인 성별. 남성, 여성 순이다. */
    public List<Gender> allowedGenders() {
        return Stream.of(Gender.values()).filter(gender -> isYes(genderCode(gender))).toList();
    }

    /** 그룹({@code LifeAttribute.values()} 등)에서 코드가 Y인 속성. 선언 순서이며 해당사항없음이 마지막이다. */
    public <A extends ProfileAttribute> List<A> included(A[] group) {
        return Stream.of(group).filter(attribute -> isYes(attribute.jaCode())).toList();
    }

    static String genderCode(Gender gender) {
        return switch (gender) {
            case MALE -> MALE;
            case FEMALE -> FEMALE;
        };
    }

    private static Integer age(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }
}
