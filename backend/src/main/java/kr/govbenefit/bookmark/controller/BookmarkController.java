package kr.govbenefit.bookmark.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.bookmark.dto.BookmarkListRequest;
import kr.govbenefit.bookmark.dto.BookmarkListResponse;
import kr.govbenefit.bookmark.service.BookmarkService;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 북마크(REQ-SAVE-01의 관심 목록). 사용자는 토큰으로만 정한다(결정 0018).
 */
@Tag(name = "북마크", description = "다시 보고 싶은 혜택을 모아 두는 목록. 혜택은 검색·상세와 같은 서비스 ID로 가리킨다.")
@RestController
@RequestMapping("/api/me/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private static final String SERVICE_ID = "서비스 ID. gov.kr 상세 주소의 마지막 부분과 같다.";
    private static final String UNAUTHENTICATED = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""";
    private static final String UNAUTHENTICATED_EXAMPLE = """
            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""";

    private final BookmarkService bookmarkService;

    @Operation(summary = "북마크 저장", description = """
            혜택을 북마크에 넣는다. 이미 있으면 저장 시각만 바뀌어 목록 맨 위로 온다. \
            다시 보내거나 동시에 보내도 한 건이다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "북마크에 있다")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "수집되지 않은 서비스 ID다. `code`는 `NOT_FOUND`이고 북마크는 바뀌지 않는다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "NOT_FOUND", "message": "요청한 경로나 자원을 찾을 수 없습니다.", "errors": []}""")))
    @PutMapping("/{serviceId}")
    public ResponseEntity<Void> save(@CurrentUserId Long userId,
                                     @Parameter(description = SERVICE_ID,
                                             schema = @Schema(type = "string", example = "654000000031"))
                                     @PathVariable String serviceId) {
        bookmarkService.save(userId, serviceId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "북마크 해제", description = """
            혜택을 북마크에서 뺀다. 저장하지 않았거나 이미 뺐거나 수집되지 않은 서비스 ID여도 같은 204다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "북마크에 없다")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> remove(@CurrentUserId Long userId,
                                       @Parameter(description = SERVICE_ID,
                                               schema = @Schema(type = "string", example = "654000000031"))
                                       @PathVariable String serviceId) {
        bookmarkService.remove(userId, serviceId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "북마크 목록", description = """
            마지막으로 저장한 순서로 돌려준다. 혜택 정보는 저장할 때의 값이 아니라 지금 저장된 값이다.

            - 출처 목록에서 사라진 혜택도 숨기지 않는다. `benefit.lastSeenAt`이 멈춘 값으로 나온다.
            - 마지막 쪽을 넘은 번호는 오류가 아니라 `items`가 빈 배열인 200이다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "북마크 목록")
    @ApiResponse(responseCode = "400", description = "요청 값이 범위를 벗어났다. `code`는 `INVALID_INPUT`이고 항목별 이유는 `errors`에 있다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.",
                             "errors": [{"field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다."}]}""")))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @GetMapping
    public ResponseEntity<BookmarkListResponse> list(@CurrentUserId Long userId,
                                                     @ParameterObject @Valid BookmarkListRequest request) {
        return ResponseEntity.ok(bookmarkService.list(userId, request.page(), request.size()));
    }
}
