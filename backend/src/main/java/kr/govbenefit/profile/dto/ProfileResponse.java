package kr.govbenefit.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.region.dto.RegionResponse;

/**
 * 프로필. 미입력 항목은 null이다. 사용자 ID는 넣지 않는다(결정 0018).
 */
@Schema(description = "프로필. 미입력 항목은 `null`이다. 저장한 적이 없으면 모든 값이 `null`이다.")
public record ProfileResponse(
        @Schema(description = "생년월일", example = "1995-03-01") LocalDate birthDate,
        @Schema(description = "생년월일로 오늘(한국 시간) 기준 계산한 만 나이", example = "31") Integer age,
        @Schema(description = "성별") Gender gender,
        @Schema(description = "거주 시도") RegionResponse sido,
        @Schema(description = "거주 시군구") RegionResponse sigungu,
        @Schema(description = "생애·직업·건강 속성. `null`이면 미입력, `[\"NONE\"]`이면 해당 없음이다.")
        List<LifeAttribute> lifeAttributes,
        @Schema(description = "가구 속성. `null`이면 미입력, `[\"NONE\"]`이면 해당 없음이다.")
        List<HouseholdAttribute> householdAttributes,
        @Schema(description = "마지막으로 저장한 시각. 저장한 적이 없으면 `null`이다.", example = "2026-10-17T10:00:00+09:00")
        OffsetDateTime updatedAt) {

    public static ProfileResponse empty() {
        return new ProfileResponse(null, null, null, null, null, null, null, null);
    }
}
