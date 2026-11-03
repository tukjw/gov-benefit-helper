package kr.govbenefit.profile.domain;

/**
 * 생애·직업·건강 속성(결정 0022). 값마다 지원조건 코드(JA03xx)를 가지며 저장은 코드로 한다. 이름은 공식 명세의 설명이다.
 * {@link #NONE}은 해당사항없음이고 다른 값과 함께 고를 수 없다. 장애인·질병/질환자·임산부·예비부모/난임은 건강에 관한
 * 민감정보(개인정보 보호법 제23조)라 고를 때 별도 동의가 필요하다(#110, DEC-PROFILE).
 */
public enum LifeAttribute implements ProfileAttribute {
    PROSPECTIVE_PARENT("JA0301", "예비부모/난임", true),
    PREGNANT("JA0302", "임산부", true),
    CHILDBIRTH_ADOPTION("JA0303", "출산/입양", false),
    FARMER("JA0313", "농업인", false),
    FISHER("JA0314", "어업인", false),
    LIVESTOCK_FARMER("JA0315", "축산업인", false),
    FORESTER("JA0316", "임업인", false),
    ELEMENTARY_STUDENT("JA0317", "초등학생", false),
    MIDDLE_SCHOOL_STUDENT("JA0318", "중학생", false),
    HIGH_SCHOOL_STUDENT("JA0319", "고등학생", false),
    UNIVERSITY_STUDENT("JA0320", "대학생/대학원생", false),
    EMPLOYEE("JA0326", "근로자/직장인", false),
    JOB_SEEKER("JA0327", "구직자/실업자", false),
    DISABLED("JA0328", "장애인", true),
    VETERAN("JA0329", "국가보훈대상자", false),
    PATIENT("JA0330", "질병/질환자", true),
    NONE("JA0322", "해당사항없음", false);

    private final String jaCode;
    private final String label;
    private final boolean sensitive;

    LifeAttribute(String jaCode, String label, boolean sensitive) {
        this.jaCode = jaCode;
        this.label = label;
        this.sensitive = sensitive;
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
    public boolean sensitive() {
        return sensitive;
    }
}
