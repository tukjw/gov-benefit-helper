package kr.govbenefit.match.domain;

/**
 * 비교 항목 하나의 판정과 이유.
 */
public record ItemJudgement(ItemStatus status, MatchReason reason) {

    static ItemJudgement met(MatchReason reason) {
        return new ItemJudgement(ItemStatus.MET, reason);
    }

    static ItemJudgement needsCheck(MatchReason reason) {
        return new ItemJudgement(ItemStatus.NEEDS_CHECK, reason);
    }
}
