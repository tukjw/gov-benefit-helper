package kr.govbenefit.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.govbenefit.account.dto.EmailVerificationConfirmRequest;
import kr.govbenefit.account.dto.EmailVerificationConfirmResponse;
import kr.govbenefit.account.dto.EmailVerificationRequest;
import kr.govbenefit.account.service.ConfirmOutcome;
import kr.govbenefit.account.service.ConfirmOutcome.Confirmed;
import kr.govbenefit.account.service.ConfirmOutcome.Rejected;
import kr.govbenefit.account.service.EmailVerificationService;
import kr.govbenefit.common.api.ApiErrorResponse;
import kr.govbenefit.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 전 이메일 인증(REQ-ACCOUNT-01, DEC-AUTH, 결정 0019). 인증번호를 맞히면 가입 티켓을 준다.
 */
@Tag(name = "계정")
@RestController
@RequestMapping("/api/auth/email-verification")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @Operation(summary = "가입 인증번호 요청", description = """
            가입할 이메일로 10분 동안 쓸 수 있는 6자리 인증번호를 보낸다. 다시 요청하면 새 번호를 보내고 이전 번호는 쓸 수 없다.

            - 이미 가입된 이메일인지는 이 단계에서 알려주지 않는다. 인증을 마친 뒤 가입할 때 409 `DUPLICATE_EMAIL`이다.
            - 같은 이메일에는 60초에 한 번, 최근 24시간 동안 10번까지 보낸다. 넘으면 429이고 `Retry-After` 헤더의 초가 지나야 \
            다시 보낼 수 있다.""")
    @ApiResponse(responseCode = "204", description = "인증번호를 보냈다")
    @ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않다. `code`는 `INVALID_INPUT`이다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "발송 제한에 걸렸다. `code`는 `TOO_MANY_REQUESTS`다.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "다시 보낼 수 있을 때까지의 초",
                    schema = @Schema(type = "integer", example = "42")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "TOO_MANY_REQUESTS", "message": "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요.", "errors": []}""")))
    @PostMapping
    public ResponseEntity<Void> request(@Valid @RequestBody EmailVerificationRequest request) {
        emailVerificationService.request(request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "가입 인증번호 확인", description = """
            가장 최근에 받은 인증번호가 맞으면 30분 동안 쓸 수 있는 가입 티켓을 준다. 가입 요청의 `verificationToken`에 넣는다.

            - `VERIFICATION_CODE_MISMATCH`: 번호가 틀렸다. 다시 입력한다. 한 번호는 5번까지 입력할 수 있다.
            - `VERIFICATION_CODE_EXPIRED`: 번호가 만료됐거나, 이미 썼거나, 다시 받아 무효가 됐다. 번호를 다시 받는다.
            - `VERIFICATION_ATTEMPTS_EXCEEDED`: 5번을 다 썼다. 맞는 번호도 거절하므로 번호를 다시 받는다.""")
    @ApiResponse(responseCode = "200", description = "가입 티켓")
    @ApiResponse(responseCode = "400", description = """
            입력 값이 올바르지 않거나(`INVALID_INPUT`), 번호가 틀렸거나(`VERIFICATION_CODE_MISMATCH`), \
            다시 받아야 한다(`VERIFICATION_CODE_EXPIRED`, `VERIFICATION_ATTEMPTS_EXCEEDED`).""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {"code": "VERIFICATION_CODE_MISMATCH", "message": "인증번호가 맞지 않습니다.", "errors": []}""")))
    @PostMapping("/confirm")
    public ResponseEntity<EmailVerificationConfirmResponse> confirm(
            @Valid @RequestBody EmailVerificationConfirmRequest request) {
        ConfirmOutcome outcome = emailVerificationService.confirm(request.email(), request.code());
        return switch (outcome) {
            case Confirmed confirmed -> ResponseEntity.ok(EmailVerificationConfirmResponse.from(confirmed));
            // 서비스가 커밋한 뒤에 던진다. 예약한 시도가 롤백되지 않는다.
            case Rejected rejected -> throw new BusinessException(rejected.code());
        };
    }
}
