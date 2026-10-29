package kr.govbenefit.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.match.domain.ItemJudgement;
import kr.govbenefit.match.domain.ItemStatus;
import kr.govbenefit.match.domain.MatchNotice;
import kr.govbenefit.match.domain.MatchOutcome;
import kr.govbenefit.match.domain.MatchReason;
import kr.govbenefit.match.domain.MatchResult;
import kr.govbenefit.match.domain.UncomparedCondition;

/**
 * 조건 비교 결과 순 혜택 목록의 한 쪽(REQ-MATCH-01, DEC-MATCH). 신청 가능 확정이 아니다.
 * 항목에는 판정과 이유만 담고, 비교한 값과 원문은 혜택 하나의 조건 비교({@link MatchResponse})에서 본다.
 * 개인 대상이 아닌 혜택은 목록에 없으므로 안내·비교하지 않은 조건·기준일이 모든 항목에 같아 한 번만 담는다.
 *
 * @param totalCount  지역 범위 안에서 목록에 나오는 전체 혜택 수(해당과 확인 필요)
 * @param matchCount      그중 해당 수
 * @param needsCheckCount 그중 확인 필요 수
 * @param regionScope 실제로 적용한 운영 기관 지역 범위
 * @param page       요청한 쪽 번호. 1부터 센다.
 * @param size       요청한 쪽 크기
 * @param judgedOn   만 나이를 계산한 기준일(서울 날짜)
 * @param items      이 쪽의 항목. 마지막 쪽을 넘으면 비어 있다.
 */
@Schema(description = """
        조건 비교 결과 순 혜택 목록(REQ-MATCH-01, DEC-MATCH). 프로필과 지원조건 코드를 비교한 것이며 신청 가능 확정이 아니다. \
        `MATCH` → `NEEDS_CHECK` 순이고 같은 결과 안에서는 이름순, 이름이 같으면 서비스 ID 순이다(검색과 같은 이름순). \
        개인 대상이 아닌 혜택(`MISMATCH`)은 목록에 없고 검색으로는 찾을 수 있다. \
        운영 기관 지역 범위(`regionScope`) 안의 혜택만 담는다.""")
public record MatchListResponse(
        @Schema(description = "지역 범위 안에서 목록에 나오는 전체 혜택 수(`MATCH`와 `NEEDS_CHECK`). "
                + "규모가 커지면 빠지거나 다음 쪽 여부로 바뀔 수 있으므로 의존하지 않는다(DEC-SEARCH).") long totalCount,
        @Schema(description = "지역 범위 안의 해당(`MATCH`) 수. 목록에서 해당이 이만큼 먼저 나온다.") long matchCount,
        @Schema(description = "지역 범위 안의 확인 필요(`NEEDS_CHECK`) 수. `totalCount`에서 `matchCount`를 뺀 값이다.")
        long needsCheckCount,
        @Schema(description = "실제로 적용한 운영 기관 지역 범위. 거주지가 없으면 요청과 관계없이 `ALL`이다.")
        RegionScope regionScope,
        @Schema(description = "요청한 쪽 번호. 1부터 센다.") int page,
        @Schema(description = "요청한 쪽 크기") int size,
        @Schema(description = "만 나이를 계산한 기준일(서울 날짜). 모든 항목에 같다.", example = "2026-10-21") LocalDate judgedOn,
        @Schema(description = """
                모든 항목에 함께 보여줄 안내.
                - `CODE_BASED`: 원문이 아니라 지원조건 코드로 비교한 결과다.
                - `GROUP_RULE_INTERPRETED`: 그룹 안은 OR, 그룹 사이는 AND로 해석했다. 검증된 관계가 아니다.
                - `AGENCY_REGION_BASED`: 지역 항목은 거주 요건이 아니라 운영 기관 소재지로 비교했다.
                - `NOT_FINAL`: 신청 가능 확정이 아니다.""") List<MatchNotice> notices,
        @Schema(description = "비교하지 않은 조건. 모든 항목에 같은 고정 목록이다.") List<UncomparedCondition> uncomparedConditions,
        @Schema(description = "이 쪽의 항목. 마지막 쪽을 넘으면 빈 배열이다.") List<MatchListItem> items) {

    private static final List<MatchNotice> NOTICES = List.of(MatchNotice.values());
    private static final List<UncomparedCondition> UNCOMPARED = List.of(UncomparedCondition.values());

    public static MatchListResponse of(long matchCount, long needsCheckCount, RegionScope regionScope, int page,
                                       int size, LocalDate judgedOn, List<MatchListItem> items) {
        return new MatchListResponse(matchCount + needsCheckCount, matchCount, needsCheckCount, regionScope, page,
                size, judgedOn, NOTICES, UNCOMPARED, items);
    }

    @Schema(description = "조건 비교 목록 항목. 판정은 같은 혜택의 조건 비교(`GET /api/me/matches/{serviceId}`)와 같다.")
    public record MatchListItem(
            @Schema(description = "혜택의 현재 정보. 검색 결과 항목과 같다. 목록에서 사라진 혜택도 숨기지 않으며 "
                    + "`lastSeenAt`이 멈춘다.") BenefitSummary benefit,
            @Schema(description = """
                    전체 결과.
                    - `MATCH`: 비교 항목을 지원조건 코드상 모두 충족한다.
                    - `NEEDS_CHECK`: 원문을 확인해야 하는 항목이 있다.""") MatchResult result,
            @Schema(description = "항목별 판정") MatchItemResults items) {

        public static MatchListItem of(Benefit benefit, MatchOutcome outcome) {
            return new MatchListItem(BenefitSummary.from(benefit), outcome.result(), new MatchItemResults(
                    MatchItemResult.from(outcome.personalTarget()),
                    MatchItemResult.from(outcome.age()),
                    MatchItemResult.from(outcome.gender()),
                    MatchItemResult.from(outcome.lifeAttributes()),
                    MatchItemResult.from(outcome.householdAttributes()),
                    MatchItemResult.from(outcome.agencyRegion())));
        }
    }

    @Schema(description = """
            항목별 판정. `status`와 `reason`의 뜻과 이유의 우선순위는 혜택 하나의 조건 비교와 같다. \
            비교한 값(코드와 프로필 값)은 혜택 하나의 조건 비교에서 본다.""")
    public record MatchItemResults(
            @Schema(description = "개인 대상 여부. 목록의 사용자구분으로 판정한다.") MatchItemResult personalTarget,
            @Schema(description = "연령") MatchItemResult age,
            @Schema(description = "성별") MatchItemResult gender,
            @Schema(description = "생애·직업·건강 속성") MatchItemResult lifeAttributes,
            @Schema(description = "가구 속성") MatchItemResult householdAttributes,
            @Schema(description = "운영 기관 지역. 거주 요건이 아니라 운영 기관 소재지로 비교한다.")
            MatchItemResult agencyRegion) {
    }

    @Schema(description = "비교 항목 하나의 판정과 이유")
    public record MatchItemResult(ItemStatus status, MatchReason reason) {

        static MatchItemResult from(ItemJudgement judgement) {
            return new MatchItemResult(judgement.status(), judgement.reason());
        }
    }
}
