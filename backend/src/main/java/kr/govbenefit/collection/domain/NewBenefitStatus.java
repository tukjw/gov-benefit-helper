package kr.govbenefit.collection.domain;

public enum NewBenefitStatus {
    /** 지원조건 원문을 아직 받지 않아 대상을 판단할 수 없다. */
    WAITING_CONDITIONS,
    /** 지원조건 원문을 받아 대상을 판단할 수 있다. */
    READY
}
