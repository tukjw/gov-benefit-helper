package kr.govbenefit.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import kr.govbenefit.notification.dto.NotificationSettingRequest;
import kr.govbenefit.notification.dto.NotificationSettingResponse;
import kr.govbenefit.notification.service.NotificationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 알림 설정(REQ-NOTIFY-03, DEC-NOTIFY). 사용자는 토큰으로만 정한다(결정 0018).
 */
@Tag(name = "알림 설정", description = "새 혜택 알림 켜기·끄기. 북마크는 알림 동의가 아니다.")
@RestController
@RequestMapping("/api/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private static final String UNAUTHENTICATED = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""";
    private static final String UNAUTHENTICATED_EXAMPLE = """
            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""";

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "내 알림 설정", description = "설정한 적이 없으면 꺼짐(`false`)이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "토큰 주인의 알림 설정")
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @GetMapping
    public ResponseEntity<NotificationSettingResponse> get(@CurrentUserId Long userId) {
        return ResponseEntity.ok(notificationSettingService.get(userId));
    }

    @Operation(summary = "내 알림 설정 변경", description = """
            본문에 보낸 항목만 바꾼다. 같은 값을 다시 보내도 결과가 같다.

            - 지금은 항목이 `newBenefit` 하나라 꼭 보낸다. 없거나 `null`이면 거절한다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "보낸 값으로 설정돼 있다")
    @ApiResponse(responseCode = "400", description = """
            `newBenefit`이 없거나 `null`이거나 본문을 읽을 수 없다. `code`는 `INVALID_INPUT`이다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.", "errors": [
                              {"field": "newBenefit", "value": null, "reason": "새 혜택 알림을 켤지 끌지 보내야 합니다."}
                            ]}""")))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = UNAUTHENTICATED_EXAMPLE)))
    @PatchMapping
    public ResponseEntity<Void> update(@CurrentUserId Long userId,
                                       @Valid @RequestBody NotificationSettingRequest request) {
        notificationSettingService.update(userId, request);
        return ResponseEntity.noContent().build();
    }
}
