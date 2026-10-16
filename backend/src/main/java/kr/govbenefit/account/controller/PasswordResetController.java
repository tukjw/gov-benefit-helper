package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.account.dto.PasswordResetConfirmRequest;
import kr.govbenefit.account.dto.PasswordResetRequest;
import kr.govbenefit.account.service.PasswordResetService;
import kr.govbenefit.common.api.ApiErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 재설정(REQ-ACCOUNT-01, DEC-AUTH, 결정 0019). 요청은 가입 여부를 드러내지 않는다.
 */
@Tag(name = "계정")
@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(summary = "비밀번호 재설정 요청", description = """
            가입된 이메일이면 30분 동안 쓸 수 있는 재설정 링크(`<재설정 화면>#token=<토큰>`)를 메일로 보낸다. \
            다시 요청하면 이전 링크는 쓸 수 없다.

            - 가입되지 않은 이메일이어도, 발송 제한(60초에 한 번, 최근 24시간 10번)에 걸려도 같은 204다. 가입 여부를 드러내지 않는다.""")
    @ApiResponse(responseCode = "204", description = "요청을 받았다. 메일을 보냈는지는 알려주지 않는다.")
    @ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않다. `code`는 `INVALID_INPUT`이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping
    public ResponseEntity<Void> request(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "비밀번호 재설정", description = """
            재설정 링크의 토큰으로 비밀번호를 바꾼다. 토큰은 한 번만 쓸 수 있다. 성공하면 그 계정의 모든 로그인이 끝나므로 \
            새 비밀번호로 다시 로그인한다. 이미 발급된 액세스 토큰은 만료(최대 15분)까지 유효하다.

            - 새 비밀번호는 가입과 같은 규칙(8자 이상, UTF-8 72바이트 이하)이다. 입력 오류의 `value`는 항상 `null`이다.
            - 토큰이 만료됐거나, 이미 썼거나, 다시 요청해 무효가 됐으면 `INVALID_RESET_TOKEN`이다. 재설정을 다시 요청한다.""")
    @ApiResponse(responseCode = "204", description = "비밀번호를 바꿨다")
    @ApiResponse(responseCode = "400", description = """
            입력 값이 올바르지 않거나(`INVALID_INPUT`), 토큰을 쓸 수 없다(`INVALID_RESET_TOKEN`).""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "INVALID_RESET_TOKEN", "message": "비밀번호 재설정 링크가 유효하지 않습니다. 재설정을 다시 요청해 주세요.", "errors": []}""")))
    @PostMapping("/confirm")
    public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
