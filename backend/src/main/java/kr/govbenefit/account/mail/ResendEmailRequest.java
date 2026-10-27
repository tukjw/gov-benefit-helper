package kr.govbenefit.account.mail;

import java.util.List;

/**
 * Resend {@code POST /emails} 요청 본문. 본문은 텍스트만 보낸다(결정 0032).
 */
record ResendEmailRequest(String from, List<String> to, String subject, String text) {

    /** 로그에 남아도 수신 주소와 본문(인증번호·링크)이 드러나지 않게 한다. */
    @Override
    public String toString() {
        return "ResendEmailRequest[subject=" + subject + "]";
    }
}
