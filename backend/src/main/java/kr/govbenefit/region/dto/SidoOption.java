package kr.govbenefit.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "시도와 그 시군구")
public record SidoOption(
        @Schema(description = "행정표준기관코드", example = "6110000") String code,
        @Schema(description = "이름", example = "서울특별시") String name,
        @Schema(description = "시군구 목록. 세종·제주는 빈 배열이다.") List<RegionResponse> sigungus) {
}
