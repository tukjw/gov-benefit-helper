package kr.govbenefit.profile.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 생애·직업·건강 속성(결정 0022). 값마다 지원조건 코드(JA03xx)를 가지며 저장은 코드로 한다. 이름은 공식 명세의 설명이다.
 * {@link #NONE}은 해당사항없음이고 다른 값과 함께 고를 수 없다.
 */
public enum LifeAttribute implements ProfileAttribute {
    PROSPECTIVE_PARENT("JA0301", "예비부모/난임"),
    PREGNANT("JA0302", "임산부"),
    CHILDBIRTH_ADOPTION("JA0303", "출산/입양"),
    FARMER("JA0313", "농업인"),
    FISHER("JA0314", "어업인"),
    LIVESTOCK_FARMER("JA0315", "축산업인"),
    FORESTER("JA0316", "임업인"),
    ELEMENTARY_STUDENT("JA0317", "초등학생"),
    MIDDLE_SCHOOL_STUDENT("JA0318", "중학생"),
    HIGH_SCHOOL_STUDENT("JA0319", "고등학생"),
    UNIVERSITY_STUDENT("JA0320", "대학생/대학원생"),
    EMPLOYEE("JA0326", "근로자/직장인"),
    JOB_SEEKER("JA0327", "구직자/실업자"),
    DISABLED("JA0328", "장애인"),
    VETERAN("JA0329", "국가보훈대상자"),
    PATIENT("JA0330", "질병/질환자"),
    NONE("JA0322", "해당사항없음");

    private final String jaCode;
    private final String label;

    LifeAttribute(String jaCode, String label) {
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

    public static Optional<LifeAttribute> fromJaCode(String jaCode) {
        return Arrays.stream(values()).filter(value -> value.jaCode.equals(jaCode)).findFirst();
    }
}
