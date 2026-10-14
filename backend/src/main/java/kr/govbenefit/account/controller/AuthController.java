package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.common.security.CsrfOriginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일·비밀번호 가입, 로그인, 로그인 유지(재발급), 로그아웃(REQ-ACCOUNT-01, DEC-AUTH). 액세스 토큰 없이 호출한다.
 * 재발급과 로그아웃은 리프레시 토큰 쿠키로만 판단한다(결정 0017).
 */
@Tag(name = "계정", description = "이메일·비밀번호 가입, 로그인, 로그인 유지, 로그아웃, 내 정보")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE_DESCRIPTION = """
            리프레시 토큰 쿠키. `refresh_token=<토큰>; Path=/api/auth; Max-Age=1209600; Secure; HttpOnly; SameSite=Strict`""";
    private static final String REFRESH_COOKIE_PARAMETER = "리프레시 토큰. 로그인·재발급 응답이 준 쿠키를 브라우저가 붙인다.";

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

    @Operation(summary = "액세스 토큰 재발급", description = """
            `refresh_token` 쿠키의 리프레시 토큰으로 새 액세스 토큰을 받는다. 리프레시 토큰도 새로 발급되어 쿠키가 바뀌고, \
            새 토큰은 이때부터 14일 동안 쓸 수 있다.

            - `X-Requested-With` 헤더(값은 아무것이나)를 보내야 한다. CSRF 방어다.
            - `Authorization` 헤더는 읽지 않는다. 만료된 액세스 토큰을 붙여 보내도 된다.
            - 교체된 이전 리프레시 토큰이 다시 쓰이면 거절하고 같은 로그인의 토큰을 모두 폐기한다. 단 교체 직후(10초)에는 \
            거절만 한다. 다른 탭이 먼저 재발급했으면 쿠키가 이미 바뀌었으므로, 401이면 잠시 뒤 한 번 다시 시도하고 그래도 401이면 \
            다시 로그인한다.""",
            parameters = @Parameter(name = CsrfOriginInterceptor.REQUIRED_HEADER, in = ParameterIn.HEADER, required = true,
                    description = "CSRF 방어용 헤더. 값은 검사하지 않는다.", schema = @Schema(type = "string", example = "fetch")))
    @ApiResponse(responseCode = "200", description = "새 액세스 토큰", headers = @Header(name = HttpHeaders.SET_COOKIE,
            description = REFRESH_COOKIE_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = """
            리프레시 토큰이 없거나, 형식이 틀렸거나, 만료·교체·폐기됐다. `code`는 `UNAUTHENTICATED`다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "UNAUTHENTICATED", "message": "인증이 필요합니다.", "errors": []}""")))
    @ApiResponse(responseCode = "403", description = """
            `X-Requested-With` 헤더가 없거나 허용하지 않은 출처(`Origin`)의 요청이다. `code`는 `CSRF_REJECTED`다.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "CSRF_REJECTED", "message": "허용되지 않은 출처의 요청입니다.", "errors": []}""")))
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Parameter(description = REFRESH_COOKIE_PARAMETER)
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        // 거절할 때 쿠키를 지우지 않는다. 동시 재발급에서 진 응답이 이긴 응답의 새 쿠키를 지울 수 있다.
        AuthTokens tokens = accountService.refresh(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()))
                .body(LoginResponse.from(tokens.accessToken()));
    }

    @Operation(summary = "로그아웃", description = """
            `refresh_token` 쿠키의 리프레시 토큰과 같은 로그인에서 이어진 토큰을 모두 폐기하고 쿠키를 지운다. \
            쿠키가 없거나 쓸 수 없는 토큰이어도 같은 응답이다. 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하므로 \
            클라이언트가 버린다.""")
    @ApiResponse(responseCode = "204", description = "로그아웃했다", headers = @Header(name = HttpHeaders.SET_COOKIE,
            description = "`refresh_token`을 지우는 쿠키(`Max-Age=0`)", schema = @Schema(type = "string")))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Parameter(description = REFRESH_COOKIE_PARAMETER)
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        accountService.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear()).build();
    }
}
