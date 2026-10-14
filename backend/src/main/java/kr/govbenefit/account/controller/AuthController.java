package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.account.dto.AccountResponse;
import kr.govbenefit.account.dto.LoginRequest;
import kr.govbenefit.account.dto.LoginResponse;
import kr.govbenefit.account.dto.SignUpRequest;
import kr.govbenefit.account.service.AccountService;
import kr.govbenefit.account.service.AuthTokens;
import kr.govbenefit.common.api.ApiErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일·비밀번호 가입과 로그인(REQ-ACCOUNT-01, DEC-AUTH). 로그인 없이 호출한다.
 */
@Tag(name = "계정", description = "이메일·비밀번호 가입, 로그인, 내 정보")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE_DESCRIPTION = """
            리프레시 토큰 쿠키. `refresh_token=<토큰>; Path=/api/auth; Max-Age=1209600; Secure; HttpOnly; SameSite=Strict`""";

    private final AccountService accountService;
    private final RefreshTokenCookies refreshTokenCookies;

    @Operation(summary = "가입", description = """
            이메일과 비밀번호로 가입한다. 토큰은 주지 않으므로 이어서 로그인한다.

            - 이메일은 대소문자를 구분하지 않는다. 대소문자만 다른 이메일도 이미 가입된 이메일이다.
            - 비밀번호는 8자 이상, UTF-8 72바이트 이하다(한글만 쓰면 24자).
            - 입력 오류의 `errors`에서 비밀번호 항목의 `value`는 항상 `null`이다.""")
    @ApiResponse(responseCode = "201", description = "가입한 계정")
    @ApiResponse(responseCode = "400", description = "입력 값이 올바르지 않다. `code`는 `INVALID_INPUT`이고 항목별 이유는 `errors`에 있다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_INPUT", "message": "요청 값이 올바르지 않습니다.",
                             "errors": [{"field": "password", "value": null, "reason": "8자 이상이어야 합니다."}]}""")))
    @ApiResponse(responseCode = "409", description = "이미 가입된 이메일이다. `code`는 `DUPLICATE_EMAIL`이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "DUPLICATE_EMAIL", "message": "이미 가입된 이메일입니다.", "errors": []}""")))
    @PostMapping("/signup")
    public ResponseEntity<AccountResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.signUp(request));
    }

    @Operation(summary = "로그인", description = """
            맞는 이메일·비밀번호면 15분 동안 쓸 수 있는 액세스 토큰을 본문으로, 14일 동안 쓸 수 있는 리프레시 토큰을 \
            `refresh_token` 쿠키로 준다. 비밀번호가 틀린 경우와 가입되지 않은 이메일은 같은 응답이다.""")
    @ApiResponse(responseCode = "200", description = "액세스 토큰", headers = @Header(name = HttpHeaders.SET_COOKIE,
            description = REFRESH_COOKIE_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "이메일이나 비밀번호가 비어 있다. `code`는 `INVALID_INPUT`이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 맞지 않다. `code`는 `INVALID_CREDENTIALS`다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_CREDENTIALS", "message": "이메일 또는 비밀번호가 올바르지 않습니다.", "errors": []}""")))
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthTokens tokens = accountService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()))
                .body(LoginResponse.from(tokens.accessToken()));
    }
}
