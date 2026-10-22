package kr.govbenefit.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.OnlineApplyUrl;
import kr.govbenefit.match.domain.ItemStatus;
import kr.govbenefit.match.domain.MatchNotice;
import kr.govbenefit.match.domain.MatchOutcome;
import kr.govbenefit.match.domain.MatchReason;
import kr.govbenefit.match.domain.MatchResult;
import kr.govbenefit.match.domain.MatchSubject;
import kr.govbenefit.match.domain.SupportConditions;
import kr.govbenefit.match.domain.UncomparedCondition;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;

/**
 * 혜택 하나의 조건 비교 결과(REQ-MATCH-01, DEC-MATCH). 신청 가능 확정이 아니다.
 * 항목마다 판정·이유와 비교한 값(혜택의 코드, 프로필 값)을 담는다.
 *
 * @param judgedOn 만 나이를 계산한 기준일(서울 날짜)
 */
@Schema(description = """
        혜택 하나의 조건 비교 결과(REQ-MATCH-01, DEC-MATCH). 프로필과 지원조건 코드를 비교한 것이며 신청 가능 확정이 아니다. \
        결과와 관계없이 원문(`source`)과 공식 링크로 확인한다.""")
public record MatchResponse(
        @Schema(description = "서비스 ID", example = "654000000031") String serviceId,
        @Schema(description = "혜택 이름") String name,
        @Schema(description = """
                전체 결과.
                - `MATCH`: 비교 항목을 지원조건 코드상 모두 충족한다.
                - `NEEDS_CHECK`: 원문을 확인해야 하는 항목이 있다.
                - `MISMATCH`: 개인 대상이 아닌 혜택이다. 개인 대상 혜택에는 쓰지 않는다.""") MatchResult result,
        @Schema(description = "만 나이를 계산한 기준일(서울 날짜)", example = "2026-10-21") LocalDate judgedOn,
        @Schema(description = """
                함께 보여줄 안내.
                - `CODE_BASED`: 원문이 아니라 지원조건 코드로 비교한 결과다.
                - `GROUP_RULE_INTERPRETED`: 그룹 안은 OR, 그룹 사이는 AND로 해석했다. 검증된 관계가 아니다.
                - `NOT_FINAL`: 신청 가능 확정이 아니다. 모든 결과에 있다.
                `MISMATCH`는 사용자구분으로 판정하므로 `NOT_FINAL`만 있다.""") List<MatchNotice> notices,
        @Schema(description = "비교하지 않은 조건. 혜택마다 같은 고정 목록이다.") List<UncomparedCondition> uncomparedConditions,
        @Schema(description = "항목별 판정") MatchItems items,
        @Schema(description = "확인할 원문과 공식 링크") MatchSource source) {

    private static final List<MatchNotice> ALL_NOTICES = List.of(MatchNotice.values());
    private static final List<MatchNotice> MISMATCH_NOTICES = List.of(MatchNotice.NOT_FINAL);
    private static final List<UncomparedCondition> UNCOMPARED = List.of(UncomparedCondition.values());

    /**
     * @param conditions 지원조건을 수집하지 못했으면 null이다.
     */
    public static MatchResponse of(Benefit benefit, SupportConditions conditions, MatchSubject subject,
                                   MatchOutcome outcome, LocalDate judgedOn) {
        MatchItems items = new MatchItems(
                new PersonalTargetItem(outcome.personalTarget().status(), outcome.personalTarget().reason(),
                        benefit.getUserType()),
                new AgeItem(outcome.age().status(), outcome.age().reason(),
                        conditions == null ? null : conditions.ageStart(),
                        conditions == null ? null : conditions.ageEnd(), subject.age()),
                new GenderItem(outcome.gender().status(), outcome.gender().reason(),
                        conditions == null ? null : conditions.allowedGenders(), subject.gender()),
                new LifeAttributeItem(outcome.lifeAttributes().status(), outcome.lifeAttributes().reason(),
                        conditions == null ? null : conditions.included(LifeAttribute.values()),
                        subject.lifeAttributes()),
                new HouseholdAttributeItem(outcome.householdAttributes().status(),
                        outcome.householdAttributes().reason(),
                        conditions == null ? null : conditions.included(HouseholdAttribute.values()),
                        subject.householdAttributes()));
        return new MatchResponse(benefit.getServiceId(), benefit.getName(), outcome.result(), judgedOn,
                outcome.result() == MatchResult.MISMATCH ? MISMATCH_NOTICES : ALL_NOTICES, UNCOMPARED, items,
                new MatchSource(benefit.getTarget(), benefit.getSelectionCriteria(),
                        OnlineApplyUrl.toLink(benefit.getDetailUrl())));
    }

    @Schema(description = """
            항목별 판정. `status`는 `MET`(충족)·`NEEDS_CHECK`(확인 필요)·`NOT_MET`(미충족, 개인 대상에만)·\
            `NOT_EVALUATED`(개인 대상이 아니라 판정하지 않음)다. `reason`은 처음 해당하는 이유 하나이며, \
            지원조건 미수집 → 코드 없음·해석 불가 → 프로필 미입력 → 비교 결과 순으로 정한다. \
            그래서 혜택에 제한이 없어도(`NO_RESTRICTION`이 될 항목) 프로필이 미입력이면 `PROFILE_MISSING`이다.""")
    public record MatchItems(
            @Schema(description = "개인 대상 여부. 목록의 사용자구분으로 판정한다.") PersonalTargetItem personalTarget,
            @Schema(description = "연령") AgeItem age,
            @Schema(description = "성별") GenderItem gender,
            @Schema(description = "생애·직업·건강 속성") LifeAttributeItem lifeAttributes,
            @Schema(description = "가구 속성") HouseholdAttributeItem householdAttributes) {
    }

    @Schema(description = "개인 대상 여부. 사용자구분에 개인 또는 가구가 있으면 충족, 둘 다 없으면 미충족, 비어 있으면 확인 필요다.")
    public record PersonalTargetItem(
            ItemStatus status,
            MatchReason reason,
            @Schema(description = "사용자구분 원문. 여러 값은 `||`로 이어 붙어 있다.", example = "개인||가구") String userType) {
    }

    @Schema(description = """
            연령. 코드의 시작·종료를 포함 범위로 읽되 경계값과 같으면 확인 필요다. \
            시작 0은 하한 없음, 종료 120은 상한 없음이라 경계값으로 치지 않는다.""")
    public record AgeItem(
            ItemStatus status,
            MatchReason reason,
            @Schema(description = "대상연령 시작 코드. 없으면 `null`이다.", example = "19") Integer ageStart,
            @Schema(description = "대상연령 종료 코드. 없으면 `null`이다.", example = "39") Integer ageEnd,
            @Schema(description = "기준일의 만 나이. 생년월일이 미입력이면 `null`이다.", example = "30") Integer profileAge) {
    }

    @Schema(description = "성별. 두 성별이 모두 대상이면 제한 없음이다.")
    public record GenderItem(
            ItemStatus status,
            MatchReason reason,
            @Schema(description = "코드가 대상으로 표시한 성별. 지원조건을 수집하지 못했으면 `null`, 코드가 없으면 빈 배열이다.")
            List<Gender> allowedGenders,
            @Schema(description = "프로필 성별. 미입력이면 `null`이다.") Gender profileGender) {
    }

    @Schema(description = """
            생애·직업·건강 속성. 하나라도 대상에 포함되면 충족이다(그룹 안 OR, 해석 규칙). \
            모두 대상이거나 해당사항없음(`NONE`)만 대상이면 제한 없음이다.""")
    public record LifeAttributeItem(
            ItemStatus status,
            MatchReason reason,
            @Schema(description = "코드가 대상으로 표시한 속성. 지원조건을 수집하지 못했으면 `null`, 코드가 없으면 빈 배열이다.")
            List<LifeAttribute> includedAttributes,
            @Schema(description = "프로필 속성. 미입력이면 `null`, 해당 없음이면 `[\"NONE\"]`이다.")
            List<LifeAttribute> profileAttributes) {
    }

    @Schema(description = """
            가구 속성. 하나라도 대상에 포함되면 충족이다(그룹 안 OR, 해석 규칙). \
            모두 대상이거나 해당사항없음(`NONE`)만 대상이면 제한 없음이다.""")
    public record HouseholdAttributeItem(
            ItemStatus status,
            MatchReason reason,
            @Schema(description = "코드가 대상으로 표시한 속성. 지원조건을 수집하지 못했으면 `null`, 코드가 없으면 빈 배열이다.")
            List<HouseholdAttribute> includedAttributes,
            @Schema(description = "프로필 속성. 미입력이면 `null`, 해당 없음이면 `[\"NONE\"]`이다.")
            List<HouseholdAttribute> profileAttributes) {
    }

    @Schema(description = "확인할 원문과 공식 링크. 값은 원문 그대로이며 없으면 `null`이다.")
    public record MatchSource(
            @Schema(description = "지원 대상 원문") String target,
            @Schema(description = "선정 기준 원문") String selectionCriteria,
            @Schema(description = "공식 출처(gov.kr 상세 페이지). 링크로 쓸 수 없으면 `null`이다.",
                    example = "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/654000000031")
            String guideUrl) {
    }
}
