package kr.govbenefit.benefit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 목록(serviceList) 기준 혜택. 출처 서비스ID로 식별한다.
 */
@Entity
@Table(name = "benefit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Benefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceId;

    private String name;

    private String purposeSummary;

    private String serviceField;

    private String userType;

    private String supportType;

    @Column(columnDefinition = "text")
    private String target;

    @Column(columnDefinition = "text")
    private String selectionCriteria;

    @Column(columnDefinition = "text")
    private String supportContent;

    private String applyPeriodText;

    private String applyMethodType;

    @Column(columnDefinition = "text")
    private String phoneInquiry;

    private String receptionAgency;

    private String agencyName;

    private String agencyType;

    private String agencyCode;

    private String departmentName;

    private String detailUrl;

    /** 목록에서 마지막으로 확인한 시각. 목록에서 빠지면 이전 값을 유지한다. */
    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    /** 내용 컬럼이 마지막으로 바뀐 시각. */
    private LocalDateTime updatedAt;

    public static Benefit create(String serviceId, BenefitContent content, LocalDateTime now) {
        Benefit benefit = new Benefit();
        benefit.serviceId = serviceId;
        benefit.setContent(content);
        benefit.lastSeenAt = now;
        benefit.createdAt = now;
        benefit.updatedAt = now;
        return benefit;
    }

    /**
     * 목록에서 다시 확인했다. 내용이 바뀐 경우에만 내용과 수정 시각을 바꾼다.
     */
    public void confirm(BenefitContent content, LocalDateTime now) {
        if (!content.equals(content())) {
            setContent(content);
            this.updatedAt = now;
        }
        this.lastSeenAt = now;
    }

    public BenefitContent content() {
        return new BenefitContent(name, purposeSummary, serviceField, userType, supportType, target,
                selectionCriteria, supportContent, applyPeriodText, applyMethodType, phoneInquiry, receptionAgency,
                agencyName, agencyType, agencyCode, departmentName, detailUrl);
    }

    private void setContent(BenefitContent content) {
        this.name = content.name();
        this.purposeSummary = content.purposeSummary();
        this.serviceField = content.serviceField();
        this.userType = content.userType();
        this.supportType = content.supportType();
        this.target = content.target();
        this.selectionCriteria = content.selectionCriteria();
        this.supportContent = content.supportContent();
        this.applyPeriodText = content.applyPeriodText();
        this.applyMethodType = content.applyMethodType();
        this.phoneInquiry = content.phoneInquiry();
        this.receptionAgency = content.receptionAgency();
        this.agencyName = content.agencyName();
        this.agencyType = content.agencyType();
        this.agencyCode = content.agencyCode();
        this.departmentName = content.departmentName();
        this.detailUrl = content.detailUrl();
    }
}
