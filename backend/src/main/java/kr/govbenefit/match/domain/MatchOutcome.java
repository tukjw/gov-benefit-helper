package kr.govbenefit.match.domain;

/**
 * 조건 비교의 전체 결과와 항목별 판정.
 */
public record MatchOutcome(
        MatchResult result,
        ItemJudgement personalTarget,
        ItemJudgement age,
        ItemJudgement gender,
        ItemJudgement lifeAttributes,
        ItemJudgement householdAttributes) {
}
