package kr.govbenefit.account.domain;

import java.util.Locale;

/**
 * 이메일을 저장·조회할 형태로 바꾼다. 대소문자만 다른 이메일은 같은 계정이다.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
