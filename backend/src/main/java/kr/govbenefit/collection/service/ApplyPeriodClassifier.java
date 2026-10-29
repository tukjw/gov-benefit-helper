package kr.govbenefit.collection.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;

/**
 * 신청기한 원문을 세부 규칙 하나로 분류한다. 규칙은 위에서부터 처음 맞는 것 하나를 쓴다.
 * #5의 {@code scripts/research/gov24/queries/deadline_classes.sql}과 같은 순서·정규식이며, 복수 기간 규칙만 더했다.
 */
final class ApplyPeriodClassifier {

    private static final Pattern NOT_REQUIRED = Pattern.compile(
            "신청\\s*(절차\\s*)?(가\\s*)?(불필요|불요)|신청\\s*절차\\s*(가|는)?\\s*없"
                    + "|별도\\s*(의)?\\s*신청\\s*(절차)?\\s*(가|는)?\\s*(없|불필요)"
                    + "|자동\\s*(지급|지원|감면|부과|적용|선정)");
    private static final Pattern FULL_DATE = Pattern.compile(
            "(19|20)\\d{2}\\s*[.\\-/년]\\s*\\d{1,2}\\s*[.\\-/월]\\s*\\d{1,2}");
    private static final Pattern PERIOD_SEPARATOR = Pattern.compile("[~∼〜～]");
    private static final Pattern EARLY_CLOSE = Pattern.compile("예산|소진|선착순|조기\\s*마감|마감\\s*될\\s*수");
    // 기간 숫자가 있어야 사건 기준으로 본다. '시행일로부터 지정기간'처럼 기간이 없으면 기한을 알 수 없다.
    private static final Pattern EVENT_BASED = Pattern.compile(
            "(\\d+|한|두|세)\\s*(일|개월|달|월|년|주)\\s*(이내|내에|안에|전부터|전까지|전)"
                    + "|(로부터|후)\\s*\\d+\\s*(일|개월|달|월|년|주)");
    private static final Pattern BUDGET_OR_FIRST_COME = Pattern.compile(
            "예산\\s*(소진|한도|범위|내|의\\s*범위)|소진\\s*(시|될|되면|까지)|선착순|조기\\s*마감|마감\\s*될\\s*수");
    private static final Pattern ALWAYS = Pattern.compile("상시|연중|수시|언제든|365일|항시");
    private static final Pattern YEAR_MONTH = Pattern.compile("(19|20)\\d{2}\\s*[.\\-/년]\\s*\\d{1,2}");
    private static final Pattern NONE_STATED = Pattern.compile("^\\s*(해당\\s*없음|해당사항\\s*없음|없음|-)\\s*$");
    private static final Pattern VARIES_BY_AGENCY = Pattern.compile("상이|다를\\s*수|다름|따라\\s*다|마다\\s*다|별로\\s*다");
    private static final Pattern SEE_NOTICE = Pattern.compile("공고|공지|별도|추후|안내|홈페이지|참고|참조|따름|따라|문의|확인");
    private static final Pattern RECURRING = Pattern.compile(
            "매년|매월|매달|분기|해마다|연\\s*\\d+\\s*회|상반기|하반기|\\d{1,2}\\s*월|연초|연말|설|추석|초순|중순|하순|학기");

    private ApplyPeriodClassifier() {
    }

    static ApplyPeriodRule classify(String text) {
        if (text == null || text.isBlank()) {
            return ApplyPeriodRule.NO_VALUE;
        }
        if (NOT_REQUIRED.matcher(text).find()) {
            return ApplyPeriodRule.NOT_REQUIRED;
        }
        int dates = count(FULL_DATE, text);
        if (dates > 0) {
            if (dates >= 3 || count(PERIOD_SEPARATOR, text) >= 2) {
                return ApplyPeriodRule.MULTIPLE_PERIODS;
            }
            return EARLY_CLOSE.matcher(text).find() ? ApplyPeriodRule.DATE_WITH_EARLY_CLOSE : ApplyPeriodRule.DATE;
        }
        if (EVENT_BASED.matcher(text).find()) {
            return ApplyPeriodRule.EVENT_BASED;
        }
        if (BUDGET_OR_FIRST_COME.matcher(text).find()) {
            return ApplyPeriodRule.BUDGET_OR_FIRST_COME;
        }
        // '접수시기'·'접수시'의 '수시'를 상시로 읽지 않는다.
        if (ALWAYS.matcher(text.replace("접수시", "")).find()) {
            return ApplyPeriodRule.ALWAYS;
        }
        if (YEAR_MONTH.matcher(text).find()) {
            return ApplyPeriodRule.YEAR_MONTH_ONLY;
        }
        if (NONE_STATED.matcher(text).find()) {
            return ApplyPeriodRule.NONE_STATED;
        }
        if (VARIES_BY_AGENCY.matcher(text).find()) {
            return ApplyPeriodRule.VARIES_BY_AGENCY;
        }
        if (SEE_NOTICE.matcher(text).find()) {
            return ApplyPeriodRule.SEE_NOTICE;
        }
        if (RECURRING.matcher(text).find()) {
            return ApplyPeriodRule.RECURRING;
        }
        return ApplyPeriodRule.OTHER;
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
