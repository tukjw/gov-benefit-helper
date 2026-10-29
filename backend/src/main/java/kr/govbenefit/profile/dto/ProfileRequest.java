package kr.govbenefit.profile.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 프로필 저장과 비회원 조건 비교의 요청. 본문 전체가 프로필 값이며 빠진 필드와 null은 미입력이다. 값은 문자열로 받아
 * {@code ProfileValidator}가 항목별로 검사한다. 지원하지 않는 값이 본문 읽기 실패가 되면 어느 항목인지 응답에 담을 수 없기 때문이다.
 */
@Schema(description = """
        프로필 값. 프로필 저장과 비회원 조건 비교에 쓴다. 모든 항목은 선택이며 빠진 필드와 `null`은 미입력이다. \
        사용자 ID는 받지 않는다.""")
public record ProfileRequest(
        @Schema(description = "생년월일(`yyyy-MM-dd`). 미래 날짜와 만 120세를 넘는 날짜는 거절한다.",
                example = "1995-03-01", format = "date")
        String birthDate,
        @Schema(description = "성별", allowableValues = {"MALE", "FEMALE"})
        String gender,
        @Schema(description = "거주 시도의 행정표준기관코드(결정 0021). 선택지 API의 `sidos[].code`다.",
                example = "6110000")
        String sidoCode,
        @Schema(description = """
                거주 시군구의 행정표준기관코드. `sidoCode`의 시군구여야 하며 `sidoCode` 없이 보낼 수 없다. \
                세종·제주는 시군구가 없다.""",
                example = "3220000")
        String sigunguCode,
        @ArraySchema(arraySchema = @Schema(description = """
                생애·직업·건강 속성(결정 0022). `null`이면 미입력, `["NONE"]`이면 해당 없음이다. \
                빈 배열, 중복, `NONE`과 다른 값을 함께 보내면 거절한다."""),
                schema = @Schema(allowableValues = {"PROSPECTIVE_PARENT", "PREGNANT", "CHILDBIRTH_ADOPTION", "FARMER",
                        "FISHER", "LIVESTOCK_FARMER", "FORESTER", "ELEMENTARY_STUDENT", "MIDDLE_SCHOOL_STUDENT",
                        "HIGH_SCHOOL_STUDENT", "UNIVERSITY_STUDENT", "EMPLOYEE", "JOB_SEEKER", "DISABLED", "VETERAN",
                        "PATIENT", "NONE"}))
        List<String> lifeAttributes,
        @ArraySchema(arraySchema = @Schema(description = """
                가구 속성(결정 0022). `null`이면 미입력, `["NONE"]`이면 해당 없음이다. \
                빈 배열, 중복, `NONE`과 다른 값을 함께 보내면 거절한다."""),
                schema = @Schema(allowableValues = {"MULTICULTURAL_FAMILY", "NORTH_KOREAN_DEFECTOR",
                        "SINGLE_PARENT_OR_GRANDPARENT_FAMILY", "SINGLE_PERSON_HOUSEHOLD", "MULTI_CHILD_HOUSEHOLD",
                        "NON_HOMEOWNER_HOUSEHOLD", "NEW_RESIDENT", "EXTENDED_FAMILY", "NONE"}))
        List<String> householdAttributes) {

    /** 모든 항목이 미입력인 요청. 본문 없이 온 비회원 조건 비교에 쓴다. */
    public static ProfileRequest empty() {
        return new ProfileRequest(null, null, null, null, null, null);
    }
}
