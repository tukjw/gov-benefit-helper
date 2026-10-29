package kr.govbenefit.profile.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.govbenefit.profile.dto.ProfileOptionsResponse;
import kr.govbenefit.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 입력 선택지. 비회원 조건 비교에서도 입력을 만들어야 하므로 로그인 없이 쓴다(DEC-AUTH 공개 범위).
 */
@Tag(name = "프로필", description = "조건 비교와 운영 기관 지역 필터에 쓰는 최소 프로필. 모든 항목은 선택 입력이며 소득은 받지 않는다.")
@RestController
@RequestMapping("/api/profile/options")
@RequiredArgsConstructor
public class ProfileOptionsController {

    private final ProfileService profileService;

    @Operation(summary = "프로필 선택지", description = """
            시도별 시군구 목록과 생애·가구 속성의 값·이름이다. 프로필 저장(`PUT /api/me/profile`)과 \
            비회원 조건 비교(`POST /api/matches`)의 입력 값을 여기서 고른다.""")
    @ApiResponse(responseCode = "200", description = "선택지")
    @GetMapping
    public ResponseEntity<ProfileOptionsResponse> options() {
        return ResponseEntity.ok(profileService.options());
    }
}
