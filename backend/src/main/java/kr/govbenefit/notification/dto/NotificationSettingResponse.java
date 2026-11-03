package kr.govbenefit.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.govbenefit.account.domain.UserAccount;

/**
 * 알림 설정. 사용자 ID는 넣지 않는다(결정 0018).
 */
@Schema(description = "알림 설정")
public record NotificationSettingResponse(
        @Schema(description = "새 혜택 알림을 켰는지. 설정한 적이 없으면 `false`다.", example = "false")
        boolean newBenefit) {

    public static NotificationSettingResponse from(UserAccount account) {
        return new NotificationSettingResponse(account.isNewBenefitNotify());
    }
}
