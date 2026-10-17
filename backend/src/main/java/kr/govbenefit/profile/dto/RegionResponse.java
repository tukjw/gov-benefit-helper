package kr.govbenefit.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.govbenefit.region.domain.Region;

@Schema(description = "시도 또는 시군구")
public record RegionResponse(
        @Schema(description = "행정표준기관코드(결정 0021)", example = "3220000") String code,
        @Schema(description = "이름. 시군구는 시도명을 뺀 이름이다.", example = "강남구") String name) {

    public static RegionResponse from(Region region) {
        return new RegionResponse(region.getCode(), region.getName());
    }
}
