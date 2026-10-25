package kr.govbenefit.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 공개 지역 목록(결정 0025). 지역은 행정표준기관코드라 목록 없이는 운영 기관 지역 필터 값을 만들 수 없다.
 */
@Schema(description = "지역 목록. 운영 기관 지역 필터와 프로필 거주지에 쓰는 코드다.")
public record RegionListResponse(
        @Schema(description = "시도 목록. 관례 순서이며 시군구는 같은 시도 안에서 코드 순서다.") List<SidoOption> sidos) {
}
