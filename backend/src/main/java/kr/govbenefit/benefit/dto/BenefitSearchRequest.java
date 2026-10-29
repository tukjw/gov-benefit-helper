package kr.govbenefit.benefit.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 혜택 검색 요청의 쿼리 파라미터. 받은 값과 범위 검증만 맡고, 검색에 쓸 조건은 {@link #toCondition()}이 정리한다.
 * 필터 값이 여럿이면 파라미터를 반복한다({@code field=a&field=b}).
 *
 * @param page 1부터 센 쪽 번호. 없으면 1이다.
 * @param size 쪽 크기. 없으면 20이다.
 */
public record BenefitSearchRequest(
        @Parameter(description = "혜택 이름 키워드. 공백으로 나눈 단어(최대 10개)를 모두 이름에 포함한 혜택을 찾는다. 100자까지.",
                example = "청년 월세")
        @Size(max = BenefitSearchCondition.MAX_KEYWORD_LENGTH, message = BenefitSearchRequest.KEYWORD_LENGTH)
        String keyword,
        // springdoc은 @BindParam 이름을 읽지 않으므로 명세의 파라미터 이름을 따로 적는다.
        @Parameter(name = "field", description = FILTER_VALUE + "분야 원문 값이다.", example = "생활안정")
        @BindParam("field") List<String> fields,
        @Parameter(name = "agencyType", description = FILTER_VALUE + "운영 기관 유형 원문 값이다.", example = "시군구")
        @BindParam("agencyType") List<String> agencyTypes,
        @Parameter(description = AGENCY_REGION + "시도를 고르면 그 시도 기관, 도내 시군구 기관, 교육청 혜택이다.",
                example = "6410000") String agencySidoCode,
        @Parameter(description = AGENCY_REGION + "시도와 함께 보내야 하며 그 시군구 기관 혜택만 찾는다.",
                example = "3740000") String agencySigunguCode,
        @Parameter(description = "1부터 센 쪽 번호", schema = @Schema(implementation = Integer.class, defaultValue = "1"))
        @Min(value = 1, message = BenefitSearchRequest.PAGE_RANGE) Integer page,
        @Parameter(description = "쪽 크기", schema = @Schema(implementation = Integer.class, defaultValue = "20"))
        @Min(value = 1, message = BenefitSearchRequest.SIZE_RANGE)
        @Max(value = BenefitSearchRequest.MAX_SIZE, message = BenefitSearchRequest.SIZE_RANGE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    static final String KEYWORD_LENGTH = "100자까지 입력할 수 있습니다.";
    static final String PAGE_RANGE = "1 이상이어야 합니다.";
    static final String SIZE_RANGE = "1 이상 100 이하여야 합니다.";
    static final String FILTER_VALUE = "원문 값과 같은 혜택만 찾는다. 값을 검증하지 않으므로 없는 값이면 결과가 0건이고, 빈 값은 무시한다. "
            + "반복하면 OR이다. ";
    static final String AGENCY_REGION = "운영 기관 지역(소관기관 소재지, 결정 0025). 거주 요건이 아니다. "
            + "행정표준기관코드이며 지역 목록 API(`GET /api/benefits/regions`)에서 고른다. "
            + "지역이 없는 기관(중앙행정기관, 공공기관)의 혜택은 나오지 않는다. 빈 값은 무시한다. ";

    public BenefitSearchRequest {
        page = page == null ? 1 : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public BenefitSearchCondition toCondition() {
        return BenefitSearchCondition.of(keyword, fields, agencyTypes, agencySidoCode, agencySigunguCode);
    }
}
