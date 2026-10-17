package kr.govbenefit.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;
import kr.govbenefit.profile.domain.ProfileAttribute;

/**
 * 프로필 입력의 선택지. 지역은 행정표준기관코드라 목록 없이는 값을 만들 수 없고, 속성의 한국어 이름은 명세에 없다.
 */
@Schema(description = "프로필 입력의 선택지")
public record ProfileOptionsResponse(
        @Schema(description = "시도 목록. 관례 순서이며 시군구는 같은 시도 안에서 코드 순서다.") List<SidoOption> sidos,
        @Schema(description = "생애·직업·건강 속성. `NONE`(해당사항없음)이 마지막이다.") List<AttributeOption> lifeAttributes,
        @Schema(description = "가구 속성. `NONE`(해당사항없음)이 마지막이다.") List<AttributeOption> householdAttributes) {

    @Schema(description = "시도와 그 시군구")
    public record SidoOption(
            @Schema(description = "행정표준기관코드", example = "6110000") String code,
            @Schema(description = "이름", example = "서울특별시") String name,
            @Schema(description = "시군구 목록. 세종·제주는 빈 배열이다.") List<RegionResponse> sigungus) {
    }

    @Schema(description = "속성 선택지")
    public record AttributeOption(
            @Schema(description = "요청·응답에 쓰는 값", example = "PREGNANT") String value,
            @Schema(description = "이름", example = "임산부") String label) {

        public static <E extends Enum<E> & ProfileAttribute> List<AttributeOption> of(Class<E> type) {
            return Arrays.stream(type.getEnumConstants())
                    .map(attribute -> new AttributeOption(attribute.name(), attribute.label()))
                    .toList();
        }
    }
}
