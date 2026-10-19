package kr.govbenefit.profile.domain;

/**
 * 프로필 속성 그룹({@link LifeAttribute}, {@link HouseholdAttribute})의 공통 모양. enum만 구현한다.
 */
public interface ProfileAttribute {

    String name();

    /** 지원조건 코드. 저장과 조건 비교에 쓴다. */
    String jaCode();

    /** 사람이 읽는 이름 */
    String label();
}
