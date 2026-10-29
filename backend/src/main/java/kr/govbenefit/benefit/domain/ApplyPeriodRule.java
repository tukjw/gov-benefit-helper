package kr.govbenefit.benefit.domain;

/**
 * 신청기한 원문을 분류한 세부 규칙. 분류기는 위에서부터 처음 맞는 규칙 하나를 고른다.
 * 확인 필요는 분류는 했지만 원문을 함께 봐야 하는 규칙에만 둔다(결정 0008).
 */
public enum ApplyPeriodRule {
    NO_VALUE(ApplyPeriodType.UNKNOWN, false),
    NOT_REQUIRED(ApplyPeriodType.NOT_REQUIRED, false),
    MULTIPLE_PERIODS(ApplyPeriodType.FIXED_DATE, true),
    DATE_WITH_EARLY_CLOSE(ApplyPeriodType.FIXED_DATE, true),
    DATE(ApplyPeriodType.FIXED_DATE, false),
    EVENT_BASED(ApplyPeriodType.CONDITIONAL, false),
    BUDGET_OR_FIRST_COME(ApplyPeriodType.CONDITIONAL, false),
    ALWAYS(ApplyPeriodType.ALWAYS, false),
    YEAR_MONTH_ONLY(ApplyPeriodType.UNKNOWN, false),
    NONE_STATED(ApplyPeriodType.UNKNOWN, false),
    VARIES_BY_AGENCY(ApplyPeriodType.UNKNOWN, false),
    SEE_NOTICE(ApplyPeriodType.UNKNOWN, false),
    RECURRING(ApplyPeriodType.UNKNOWN, false),
    OTHER(ApplyPeriodType.UNKNOWN, true);

    private final ApplyPeriodType type;
    private final boolean needsReview;

    ApplyPeriodRule(ApplyPeriodType type, boolean needsReview) {
        this.type = type;
        this.needsReview = needsReview;
    }

    public ApplyPeriodType type() {
        return type;
    }

    public boolean needsReview() {
        return needsReview;
    }
}
