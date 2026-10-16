package kr.govbenefit.account;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.service.ConfirmOutcome;
import kr.govbenefit.account.service.ConfirmOutcome.Confirmed;
import kr.govbenefit.account.service.EmailVerificationService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입은 인증번호 요청 → 가짜 발송에서 번호 확인 → 확인 → 가입 순서다(결정 0019). 가입이 목적이 아닌 테스트의 준비 단계에서
 * 이 순서를 한 번에 한다. 같은 이메일을 60초 안에 다시 인증하면 발송 제한에 걸린다.
 */
public class SignUpSteps {

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final RecordingAccountMailSender mailSender;

    public SignUpSteps(MockMvc mockMvc, JsonMapper jsonMapper, RecordingAccountMailSender mailSender) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.mailSender = mailSender;
    }

    public ResultActions requestCode(String email) throws Exception {
        return perform("/api/auth/email-verification", body("email", email));
    }

    public ResultActions confirm(String email, String code) throws Exception {
        return perform("/api/auth/email-verification/confirm", body("email", email, "code", code));
    }

    public ResultActions signUp(String email, String password, String verificationToken) throws Exception {
        return perform("/api/auth/signup", body("email", email, "password", password,
                "verificationToken", verificationToken));
    }

    /**
     * 인증번호를 받아 확인하고 가입 티켓을 돌려준다.
     */
    public String verify(String email) throws Exception {
        requestCode(email).andExpect(status().isNoContent());
        String response = confirm(email, mailSender.lastSignupCode(email))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonMapper.readTree(response).get("verificationToken").asString();
    }

    /**
     * 인증을 마치고 가입한다.
     */
    public void signUp(String email, String password) throws Exception {
        signUp(email, password, verify(email)).andExpect(status().isCreated());
    }

    /**
     * API를 거치지 않는 서비스 수준 테스트용. 인증번호를 받아 확인하고 가입 티켓을 돌려준다.
     */
    public static String verify(EmailVerificationService service, RecordingAccountMailSender mailSender,
                                String email) {
        service.request(email);
        ConfirmOutcome outcome = service.confirm(email, mailSender.lastSignupCode(email));
        if (!(outcome instanceof Confirmed confirmed)) {
            throw new AssertionError("인증번호 확인에 실패했습니다: " + outcome);
        }
        return confirmed.verificationToken();
    }

    private ResultActions perform(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    /** null 값도 넣을 수 있게 {@link Map#of} 대신 쓴다. */
    private static Map<String, String> body(String... keyValues) {
        Map<String, String> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put(keyValues[i], keyValues[i + 1]);
        }
        return body;
    }
}
