package kr.govbenefit.region.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거주 지역 기준(결정 0021). 코드는 행정표준기관코드로 혜택의 소관기관코드와 같다. 시드는 마이그레이션으로만 넣고 바꾸지 않는다.
 */
@Entity
@Table(name = "region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    @Id
    @Column(columnDefinition = "char(7)")
    private String code;

    /** 시도는 기관명, 시군구는 기관명에서 시도명을 뗀 값(예: 강남구) */
    private String name;

    /** 시군구의 상위 시도. 시도면 null이다. */
    @Column(columnDefinition = "char(7)")
    private String parentCode;

    private int displayOrder;

    public boolean isSido() {
        return parentCode == null;
    }
}
