package kr.govbenefit.profile.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 가구 속성(결정 0022). 값마다 지원조건 코드(JA04xx)를 가지며 저장은 코드로 한다. 이름은 공식 명세의 설명이다.
 * {@link #NONE}은 해당사항없음이고 다른 값과 함께 고를 수 없다.
 */
public enum HouseholdAttribute implements ProfileAttribute {
    MULTICULTURAL_FAMILY("JA0401", "다문화가족"),
    NORTH_KOREAN_DEFECTOR("JA0402", "북한이탈주민"),
    SINGLE_PARENT_OR_GRANDPARENT_FAMILY("JA0403", "한부모가정/조손가정"),
    SINGLE_PERSON_HOUSEHOLD("JA0404", "1인가구"),
    MULTI_CHILD_HOUSEHOLD("JA0411", "다자녀가구"),
    NON_HOMEOWNER_HOUSEHOLD("JA0412", "무주택세대"),
    NEW_RESIDENT("JA0413", "신규전입"),
    EXTENDED_FAMILY("JA0414", "확대가족"),
    NONE("JA0410", "해당사항없음");

    private final String jaCode;
    private final String label;

    HouseholdAttribute(String jaCode, String label) {
        this.jaCode = jaCode;
        this.label = label;
    }

    @Override
    public String jaCode() {
        return jaCode;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean isNone() {
        return this == NONE;
    }

    public static Optional<HouseholdAttribute> fromJaCode(String jaCode) {
        return Arrays.stream(values()).filter(value -> value.jaCode.equals(jaCode)).findFirst();
    }
}
