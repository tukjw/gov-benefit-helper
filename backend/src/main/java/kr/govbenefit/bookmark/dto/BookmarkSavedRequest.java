package kr.govbenefit.bookmark.dto;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.govbenefit.benefit.dto.BenefitSearchRequest;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 저장 여부를 확인할 서비스 ID들. 파라미터를 반복한다({@code serviceId=a&serviceId=b}).
 * 한 화면의 목록을 한 번에 확인하므로 개수 상한은 쪽 크기 상한과 같다(DEC-SEARCH).
 */
public record BookmarkSavedRequest(
        // springdoc은 @BindParam 이름을 읽지 않으므로 명세의 파라미터 이름을 따로 적는다.
        @Parameter(name = "serviceId", description = "서비스 ID. 반복해서 보낸다. 1개 이상 100개 이하다.",
                example = "654000000031")
        @BindParam("serviceId")
        @NotEmpty(message = BookmarkSavedRequest.COUNT_RANGE)
        @Size(max = BenefitSearchRequest.MAX_SIZE, message = BookmarkSavedRequest.COUNT_RANGE)
        List<String> serviceIds) {

    static final String COUNT_RANGE = "1개 이상 100개 이하여야 합니다.";
}
