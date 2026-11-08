package kr.govbenefit.account.mail;

/**
 * 새 혜택 알림 메일에 적는 혜택 하나(결정 0047).
 *
 * @param name       혜택 이름
 * @param agencyName 운영 기관
 * @param url        혜택 상세 화면 주소
 */
public record NewBenefitMailItem(String name, String agencyName, String url) {
}
