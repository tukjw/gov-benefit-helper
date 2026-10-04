package kr.govbenefit.common.api;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * API 응답 시각. 저장 시각은 Asia/Seoul 기준이고(결정 0002), 응답에는 오프셋을 붙여 내보낸다(결정 0010).
 */
public final class ApiTimes {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ApiTimes() {
    }

    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toOffsetDateTime();
    }
}
