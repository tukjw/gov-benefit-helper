package kr.govbenefit.benefit.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 원문 URL을 링크로 쓸 수 있는 형태로 정리한다(결정 0011). 링크로 쓸 수 없으면 null이며, 원문은 따로 내보낸다.
 */
public final class OnlineApplyUrl {

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
    private static final Pattern WHITESPACE = Pattern.compile("\\s", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> LINK_SCHEMES = Set.of("http", "https");
    private static final String DEFAULT_SCHEME = "https://";

    private OnlineApplyUrl() {
    }

    /**
     * 앞뒤 공백을 지우고, 스킴이 없으면 {@code https://}를 붙인다. http·https 스킴과 점이 있는 호스트를 가진 주소만 링크로
     * 인정하고, 사용자 정보가 있거나 중간에 공백이 있는 값(여러 주소·설명문)은 거절한다. 인정한 값은 받은 글자 그대로 돌려준다.
     *
     * @return 링크로 쓸 수 있는 주소. 원문이 없거나 링크로 쓸 수 없으면 null
     */
    public static String toLink(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty() || WHITESPACE.matcher(value).find()) {
            return null;
        }
        String candidate = SCHEME.matcher(value).find() ? value : DEFAULT_SCHEME + value;
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return null;
        }
        // 호스트를 해석할 수 없는 값(예: javascript:alert(1)에 스킴을 붙인 값)은 getHost()가 null이다.
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !LINK_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                || host == null || !host.contains(".") || uri.getRawUserInfo() != null) {
            return null;
        }
        return candidate;
    }
}
