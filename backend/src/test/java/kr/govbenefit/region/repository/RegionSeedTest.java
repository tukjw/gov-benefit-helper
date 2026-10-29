package kr.govbenefit.region.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.region.domain.Region;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 거주 지역 시드(결정 0021)와 교육청 코드(결정 0025). 2026-10-01 수집분의 광역시도 16개와 시군구 227개, 교육청 16곳이다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class RegionSeedTest {

    @Autowired
    private RegionRepository regionRepository;

    @Test
    void 시도_16개와_시군구_227개가_있고_모든_시군구의_상위는_시도다() {
        List<Region> regions = regionRepository.findAllByOrderByDisplayOrderAsc();
        Map<String, Region> byCode = regions.stream().collect(Collectors.toMap(Region::getCode, region -> region));

        assertThat(regions.stream().filter(Region::isSido)).hasSize(16);
        assertThat(regions.stream().filter(region -> !region.isSido())).hasSize(227)
                .allSatisfy(sigungu -> assertThat(byCode.get(sigungu.getParentCode()).isSido()).isTrue());
    }

    @Test
    void 세종과_제주는_시군구가_없다() {
        List<Region> regions = regionRepository.findAll();

        assertThat(regions).filteredOn(region -> "5690000".equals(region.getParentCode())).isEmpty();
        assertThat(regions).filteredOn(region -> "6500000".equals(region.getParentCode())).isEmpty();
        assertThat(regionRepository.findById("5690000")).get().extracting(Region::getName).isEqualTo("세종특별자치시");
        assertThat(regionRepository.findById("6500000")).get().extracting(Region::getName).isEqualTo("제주특별자치도");
    }

    @Test
    void 코드는_공공서비스_API의_소관기관코드다() {
        assertRegion("6110000", "서울특별시", null);
        assertRegion("3220000", "강남구", "6110000");
        assertRegion("6130000", "전남광주통합특별시", null);
        assertRegion("5805000", "동구", "6130000");
        assertRegion("5141000", "군위군", "6270000");
    }

    @Test
    void 시도마다_교육청_코드가_하나씩_있고_지역_코드와_겹치지_않는다() {
        List<Region> regions = regionRepository.findAll();
        List<String> codes = regions.stream().map(Region::getCode).toList();

        assertThat(regions).filteredOn(Region::isSido).hasSize(16)
                .allSatisfy(sido -> assertThat(sido.getEducationOfficeCode()).isNotNull());
        assertThat(regions).filteredOn(region -> !region.isSido())
                .allSatisfy(sigungu -> assertThat(sigungu.getEducationOfficeCode()).isNull());
        assertThat(regions.stream().map(Region::getEducationOfficeCode).filter(Objects::nonNull))
                .doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(codes);
        assertThat(regionRepository.findById("6110000")).get().extracting(Region::getEducationOfficeCode)
                .isEqualTo("7010000");
        assertThat(regionRepository.findById("6130000")).get().extracting(Region::getEducationOfficeCode)
                .isEqualTo("7140000");
        assertThat(regionRepository.findById("5690000")).get().extracting(Region::getEducationOfficeCode)
                .isEqualTo("9300000");
    }

    private void assertRegion(String code, String name, String parentCode) {
        Region region = regionRepository.findById(code).orElseThrow();
        assertThat(region.getName()).isEqualTo(name);
        assertThat(region.getParentCode()).isEqualTo(parentCode);
    }
}
