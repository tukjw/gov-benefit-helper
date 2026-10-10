package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.service.AccountService;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 계정 정보(REQ-ACCOUNT-01). 사용자는 토큰으로만 정한다(결정 0015).
 */
@Tag(name = "계정")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;

    @Operation(summary = "내 정보", description = "액세스 토큰 주인의 계정 정보를 돌려준다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "토큰 주인의 계정 정보")
    @ApiResponse(responseCode = "401", description = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다. `code`는 `UNAUTHENTICATED`다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""")))
    @GetMapping
    public AccountResponse me(@CurrentUserId Long userId) {
        return accountService.me(userId);
    }
}
