package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.dto.WithdrawalRequest;
import kr.govbenefit.account.service.AccountService;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.OpenApiConfig;
import kr.govbenefit.common.security.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 주인의 계정 정보와 탈퇴(REQ-ACCOUNT-01, DEC-AUTH). 사용자는 토큰으로만 정한다(결정 0018).
 * 탈퇴는 Authorization 헤더로만 인증하고 쿠키를 읽지 않으므로 CSRF 방어 대상이 아니다(결정 0017).
 */
@Tag(name = "계정")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;
    private final RefreshTokenCookies refreshTokenCookies;

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
    public ResponseEntity<AccountResponse> me(@CurrentUserId Long userId) {
        return ResponseEntity.ok(accountService.me(userId));
    }

    @Operation(summary = "탈퇴", description = """
            비밀번호를 다시 확인하고 계정과 프로필·북마크·토큰을 즉시 지운다. 되돌릴 수 없다. 같은 이메일로 다시 가입하면 이전 \
            데이터가 없는 새 사용자다.

            - 비밀번호가 틀리면 `PASSWORD_MISMATCH`다. 토큰은 그대로 유효하므로 다시 입력받아 보낸다.
            - 틀린 횟수는 로그인과 함께 센다. 같은 이메일·IP에서 15분 동안 5번, 같은 IP에서 15분 동안 30번 틀리면 429다.
            - 성공하면 `refresh_token` 쿠키를 지운다. 이미 받은 액세스 토큰은 클라이언트가 버린다. 남은 토큰으로 보낸 \
            개인 API 요청은 401이다.""",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "탈퇴했다", headers = @Header(name = HttpHeaders.SET_COOKIE,
            description = "`refresh_token`을 지우는 쿠키(`Max-Age=0`)", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = """
            비밀번호가 비어 있거나(`INVALID_INPUT`), 맞지 않다(`PASSWORD_MISMATCH`).""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "PASSWORD_MISMATCH", "message": "비밀번호가 맞지 않습니다.", "errors": []}""")))
    @ApiResponse(responseCode = "401", description = """
            토큰이 없거나, 형식이 틀렸거나, 위조·만료됐거나, 토큰 주인의 계정이 없다(이미 탈퇴). `code`는 `UNAUTHENTICATED`다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = """
            틀린 시도가 많아 제한에 걸렸다. 로그인과 같은 기록으로 센다. `code`는 `TOO_MANY_REQUESTS`다.""",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "다시 시도할 수 있을 때까지의 초",
                    schema = @Schema(type = "integer", example = "840")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping("/withdrawal")
    public ResponseEntity<Void> withdraw(@CurrentUserId Long userId, @Valid @RequestBody WithdrawalRequest request,
                                         HttpServletRequest httpRequest) {
        accountService.withdraw(userId, request.password(), httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear()).build();
    }
}
