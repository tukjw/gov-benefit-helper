package kr.govbenefit.benefit.dto;

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
        @Size(max = BenefitSearchCondition.MAX_KEYWORD_LENGTH, message = BenefitSearchRequest.KEYWORD_LENGTH)
        String keyword,
        @BindParam("field") List<String> fields,
        @BindParam("agencyType") List<String> agencyTypes,
        @Min(value = 1, message = BenefitSearchRequest.PAGE_RANGE) Integer page,
        @Min(value = 1, message = BenefitSearchRequest.SIZE_RANGE)
        @Max(value = BenefitSearchRequest.MAX_SIZE, message = BenefitSearchRequest.SIZE_RANGE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    static final String KEYWORD_LENGTH = "100자까지 입력할 수 있습니다.";
    static final String PAGE_RANGE = "1 이상이어야 합니다.";
    static final String SIZE_RANGE = "1 이상 100 이하여야 합니다.";

    public BenefitSearchRequest {
        page = page == null ? 1 : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public BenefitSearchCondition toCondition() {
        return BenefitSearchCondition.of(keyword, fields, agencyTypes);
    }
}
