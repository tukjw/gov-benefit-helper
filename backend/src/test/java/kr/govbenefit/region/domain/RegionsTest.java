package kr.govbenefit.region.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 운영 기관 지역 분류와 검색 필터 코드(결정 0025)를 DB 없이 검증한다. 코드는 실제 시드 값이다.
 */
class RegionsTest {

    private static final Region GYEONGGI = new Region("6410000", "경기도", null, 8, "7530000");
    private static final Region SUWON = new Region("3740000", "수원시", "6410000", 1, null);
    private static final Region UIWANG = new Region("4030000", "의왕시", "6410000", 2, null);
    private static final Region SEJONG = new Region("5690000", "세종특별자치시", null, 7, "9300000");
    private static final Region BUSAN = new Region("6260000", "부산광역시", null, 2, "7150000");
    private static final Region SUYEONG = new Region("3380000", "수영구", "6260000", 1, null);

    private final Regions regions = Regions.of(List.of(GYEONGGI, SUWON, UIWANG, SEJONG, BUSAN, SUYEONG));

    @Test
    void 중앙행정기관과_공공기관은_코드와_관계없이_지역이_없다() {
        assertThat(regions.agencyRegionOf("중앙행정기관", "1741000")).isEqualTo(AgencyRegion.NONE);
        assertThat(regions.agencyRegionOf("공공기관", "B552015")).isEqualTo(AgencyRegion.NONE);
        assertThat(regions.agencyRegionOf("공공기관", "6410000")).isEqualTo(AgencyRegion.NONE);
    }

    @Test
    void 지자체_유형은_소관기관코드로_시도나_시군구를_정한다() {
        assertThat(regions.agencyRegionOf("광역시도", "6410000")).isEqualTo(AgencyRegion.sido("6410000"));
        assertThat(regions.agencyRegionOf("시군구", "3740000")).isEqualTo(AgencyRegion.sigungu("6410000", "3740000"));
        assertThat(regions.agencyRegionOf("지방공기업", "3380000")).isEqualTo(AgencyRegion.sigungu("6260000", "3380000"));
        assertThat(regions.agencyRegionOf("지방출자_출연기관", "6260000")).isEqualTo(AgencyRegion.sido("6260000"));
    }

    @Test
    void 교육청은_소재_시도의_기관이다() {
        assertThat(regions.agencyRegionOf("교육청", "7530000")).isEqualTo(AgencyRegion.sido("6410000"));
        assertThat(regions.agencyRegionOf("교육청", "9300000")).isEqualTo(AgencyRegion.sido("5690000"));
    }

    @Test
    void 지역을_찾지_못하면_전국이_아니라_지역_모름이다() {
        assertThat(regions.agencyRegionOf("시군구", "9999999")).isEqualTo(AgencyRegion.UNKNOWN);
        assertThat(regions.agencyRegionOf("시군구", null)).isEqualTo(AgencyRegion.UNKNOWN);
        assertThat(regions.agencyRegionOf("교육청", "6410000")).isEqualTo(AgencyRegion.UNKNOWN);
        assertThat(regions.agencyRegionOf("광역시도", "7530000")).isEqualTo(AgencyRegion.UNKNOWN);
        assertThat(regions.agencyRegionOf("특별지방행정기관", "6410000")).isEqualTo(AgencyRegion.UNKNOWN);
        assertThat(regions.agencyRegionOf(null, "6410000")).isEqualTo(AgencyRegion.UNKNOWN);
    }

    @Test
    void 시도_필터는_시도와_도내_시군구_기관과_교육청이다() {
        assertThat(regions.agencyFilterOf(GYEONGGI, null))
                .isEqualTo(new AgencyFilter(List.of("6410000", "3740000", "4030000"), List.of("7530000")));
        assertThat(regions.agencyFilterOf(SEJONG, null))
                .isEqualTo(new AgencyFilter(List.of("5690000"), List.of("9300000")));
    }

    @Test
    void 시군구_필터는_그_시군구_기관만이다() {
        assertThat(regions.agencyFilterOf(GYEONGGI, SUWON)).isEqualTo(new AgencyFilter(List.of("3740000"), List.of()));
    }

    @Test
    void 내_지역_범위는_지역_없는_기관과_거주지_범위_안의_기관이다() {
        AgencyRegion gyeonggi = AgencyRegion.sido("6410000");
        AgencyRegion suwon = AgencyRegion.sigungu("6410000", "3740000");
        AgencyRegion suyeong = AgencyRegion.sigungu("6260000", "3380000");

        assertThat(AgencyRegion.NONE.isWithin("6410000", "3740000")).isTrue();
        assertThat(AgencyRegion.UNKNOWN.isWithin("6410000", "3740000")).isFalse();
        assertThat(gyeonggi.isWithin("6410000", "3740000")).isTrue();
        assertThat(suwon.isWithin("6410000", "3740000")).isTrue();
        assertThat(suwon.isWithin("6410000", "4030000")).isFalse();
        assertThat(suyeong.isWithin("6410000", "3740000")).isFalse();
        assertThat(AgencyRegion.sido("6260000").isWithin("6410000", null)).isFalse();
        // 시도만 입력했으면 판정은 확인 필요여도 도내 시군구 기관은 범위에 든다.
        assertThat(gyeonggi.isWithin("6410000", null)).isTrue();
        assertThat(suwon.isWithin("6410000", null)).isTrue();
        assertThat(suyeong.isWithin("6410000", null)).isFalse();
    }
}
