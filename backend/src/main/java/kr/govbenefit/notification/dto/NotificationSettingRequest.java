package kr.govbenefit.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 알림 설정 변경. 본문에 있는 항목만 바꾼다. 지금은 항목이 하나라 빠지면 바꿀 것이 없는 요청이다.
 */
@Schema(description = "알림 설정 변경. 보낸 항목만 바꾼다.")
public record NotificationSettingRequest(
        @Schema(description = "새 혜택 알림을 켜면 `true`, 끄면 `false`", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "새 혜택 알림을 켤지 끌지 보내야 합니다.")
        Boolean newBenefit) {
}
