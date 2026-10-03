package kr.govbenefit.benefit.domain;

/**
 * 신청기한 원문의 분류.
 */
public enum ApplyPeriodType {
    /** 연월일이 적혀 있다. */
    FIXED_DATE,
    /** 상시·연중·수시 신청. */
    ALWAYS,
    /** 사건 기준 기한이나 예산 소진·선착순처럼 조건에 따라 끝난다. */
    CONDITIONAL,
    /** 신청 절차가 없다고 적혀 있다. 기한을 모르는 것과 다르다. */
    NOT_REQUIRED,
    /** 원문으로 기한을 알 수 없다. */
    UNKNOWN
}
