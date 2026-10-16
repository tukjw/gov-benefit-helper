package kr.govbenefit.account.mail;

import java.time.Duration;

/**
 * 비밀번호 재설정 토큰을 발급했다. 트랜잭션이 커밋된 뒤 메일로 보낸다({@link AccountMailListener}).
 *
 * @param tokenId 토큰 행 ID. 발송 실패 로그에 이메일 대신 남긴다.
 */
public record PasswordResetIssued(Long tokenId, String email, String resetUrl, Duration validFor) {

    /** 로그에 남아도 링크가 드러나지 않게 한다. */
    @Override
    public String toString() {
        return "PasswordResetIssued[tokenId=" + tokenId + "]";
    }
}
