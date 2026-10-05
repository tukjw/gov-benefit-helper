package kr.govbenefit.benefit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OnlineApplyUrlTest {

    @Test
    void http와_https_주소는_그대로_쓴다() {
        assertThat(OnlineApplyUrl.toLink("https://119.busan.go.kr/")).isEqualTo("https://119.busan.go.kr/");
        assertThat(OnlineApplyUrl.toLink("http://107.kr")).isEqualTo("http://107.kr");
        assertThat(OnlineApplyUrl.toLink("https://www.gov.kr/portal/rcvfvrSvc/dtlEx/654000000031"))
                .isEqualTo("https://www.gov.kr/portal/rcvfvrSvc/dtlEx/654000000031");
    }

    @Test
    void 스킴_없는_주소에는_https를_붙인다() {
        assertThat(OnlineApplyUrl.toLink("www.hrd.go.kr")).isEqualTo("https://www.hrd.go.kr");
        assertThat(OnlineApplyUrl.toLink("booking.kywa.or.kr/apply?id=1"))
                .isEqualTo("https://booking.kywa.or.kr/apply?id=1");
    }

    @Test
    void 대소문자는_바꾸지_않는다() {
        assertThat(OnlineApplyUrl.toLink("G4B.go.kr")).isEqualTo("https://G4B.go.kr");
        assertThat(OnlineApplyUrl.toLink("HTTPS://A.GO.KR")).isEqualTo("HTTPS://A.GO.KR");
    }

    @Test
    void 앞뒤_공백은_지운다() {
        assertThat(OnlineApplyUrl.toLink("  www.hrd.go.kr\n")).isEqualTo("https://www.hrd.go.kr");
        assertThat(OnlineApplyUrl.toLink("　https://a.go.kr　")).isEqualTo("https://a.go.kr");
    }

    @Test
    void 값이_없으면_null이다() {
        assertThat(OnlineApplyUrl.toLink(null)).isNull();
        assertThat(OnlineApplyUrl.toLink("")).isNull();
        assertThat(OnlineApplyUrl.toLink("   ")).isNull();
    }

    @Test
    void 여러_주소나_설명문은_링크로_쓰지_않는다() {
        assertThat(OnlineApplyUrl.toLink("https://a.go.kr https://b.go.kr")).isNull();
        assertThat(OnlineApplyUrl.toLink("각 시군구 홈페이지 참고")).isNull();
        assertThat(OnlineApplyUrl.toLink("홈페이지")).isNull();
    }

    @Test
    void http와_https가_아닌_스킴은_링크로_쓰지_않는다() {
        assertThat(OnlineApplyUrl.toLink("javascript:alert(1)")).isNull();
        assertThat(OnlineApplyUrl.toLink("javascript://a.go.kr/%0Aalert(1)")).isNull();
        assertThat(OnlineApplyUrl.toLink("ftp://a.go.kr")).isNull();
        assertThat(OnlineApplyUrl.toLink("mailto:a@b.go.kr")).isNull();
    }

    @Test
    void 사용자_정보가_있거나_호스트에_점이_없으면_링크로_쓰지_않는다() {
        assertThat(OnlineApplyUrl.toLink("https://u@a.go.kr")).isNull();
        assertThat(OnlineApplyUrl.toLink("https://localhost/apply")).isNull();
        assertThat(OnlineApplyUrl.toLink("https://")).isNull();
    }
}
