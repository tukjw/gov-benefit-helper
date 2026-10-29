package kr.govbenefit.benefit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 혜택의 상세 항목. 행이 없으면 상세를 아직 받지 못한 혜택이다.
 */
@Entity
@Table(name = "benefit_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenefitDetail {

    @Id
    private Long benefitId;

    @Column(columnDefinition = "text")
    private String purpose;

    @Column(columnDefinition = "text")
    private String applyMethod;

    @Column(columnDefinition = "text")
    private String requiredDocuments;

    @Column(columnDefinition = "text")
    private String officialCheckDocuments;

    @Column(columnDefinition = "text")
    private String selfCheckDocuments;

    @Column(columnDefinition = "text")
    private String contact;

    private String onlineApplyUrl;

    @Column(columnDefinition = "text")
    private String laws;

    @Column(columnDefinition = "text")
    private String localLaws;

    @Column(columnDefinition = "text")
    private String adminRules;

    private String receptionAgencyName;

    private String supportType;

    private LocalDateTime createdAt;

    /** 내용 컬럼이 마지막으로 바뀐 시각. */
    private LocalDateTime updatedAt;

    public static BenefitDetail create(Long benefitId, BenefitDetailContent content, LocalDateTime now) {
        BenefitDetail detail = new BenefitDetail();
        detail.benefitId = benefitId;
        detail.setContent(content);
        detail.createdAt = now;
        detail.updatedAt = now;
        return detail;
    }

    /**
     * 상세를 다시 받았다. 내용이 바뀐 경우에만 내용과 수정 시각을 바꾼다.
     */
    public void update(BenefitDetailContent content, LocalDateTime now) {
        if (!content.equals(content())) {
            setContent(content);
            this.updatedAt = now;
        }
    }

    public BenefitDetailContent content() {
        return new BenefitDetailContent(purpose, applyMethod, requiredDocuments, officialCheckDocuments,
                selfCheckDocuments, contact, onlineApplyUrl, laws, localLaws, adminRules, receptionAgencyName,
                supportType);
    }

    private void setContent(BenefitDetailContent content) {
        this.purpose = content.purpose();
        this.applyMethod = content.applyMethod();
        this.requiredDocuments = content.requiredDocuments();
        this.officialCheckDocuments = content.officialCheckDocuments();
        this.selfCheckDocuments = content.selfCheckDocuments();
        this.contact = content.contact();
        this.onlineApplyUrl = content.onlineApplyUrl();
        this.laws = content.laws();
        this.localLaws = content.localLaws();
        this.adminRules = content.adminRules();
        this.receptionAgencyName = content.receptionAgencyName();
        this.supportType = content.supportType();
    }
}
