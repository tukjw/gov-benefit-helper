package kr.govbenefit.profile.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 최소 프로필(REQ-PROFILE-01, DEC-PROFILE). 사용자당 하나이며 모든 값은 선택 입력이라 null이 미입력이다.
 * 생애·가구 속성은 지원조건 코드로 저장하고, 그룹에 코드가 하나도 없으면 미입력이다(결정 0022).
 */
@Entity
@Table(name = "user_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    /** 계정 ID. 계정이 지워지면 함께 지워진다. */
    @Id
    private Long userId;

    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    /** 행정표준기관코드(결정 0021) */
    @Column(columnDefinition = "char(7)")
    private String sidoCode;

    @Column(columnDefinition = "char(7)")
    private String sigunguCode;

    @Getter(AccessLevel.NONE)
    @ElementCollection
    @CollectionTable(name = "user_profile_attribute", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "condition_code", columnDefinition = "char(6)")
    private Set<String> conditionCodes = new HashSet<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static UserProfile create(Long userId, LocalDateTime now) {
        UserProfile profile = new UserProfile();
        profile.userId = userId;
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }

    /**
     * 프로필 전체를 바꾼다. 값에 없는 항목은 미입력이 된다.
     */
    public void replace(ProfileValues values, LocalDateTime now) {
        birthDate = values.birthDate();
        gender = values.gender();
        sidoCode = values.sidoCode();
        sigunguCode = values.sigunguCode();
        Set<String> codes = new HashSet<>();
        if (values.lifeAttributes() != null) {
            values.lifeAttributes().forEach(attribute -> codes.add(attribute.jaCode()));
        }
        if (values.householdAttributes() != null) {
            values.householdAttributes().forEach(attribute -> codes.add(attribute.jaCode()));
        }
        // 컬렉션을 바꿔 끼우지 않고 내용만 바꿔야 바뀐 코드만 지우고 넣는다.
        conditionCodes.retainAll(codes);
        conditionCodes.addAll(codes);
        updatedAt = now;
    }

    /** 생애·직업·건강 속성. 선언 순서이며 미입력이면 null이다. */
    public List<LifeAttribute> lifeAttributes() {
        List<LifeAttribute> attributes = Arrays.stream(LifeAttribute.values())
                .filter(attribute -> conditionCodes.contains(attribute.jaCode()))
                .toList();
        return attributes.isEmpty() ? null : attributes;
    }

    /** 가구 속성. 선언 순서이며 미입력이면 null이다. */
    public List<HouseholdAttribute> householdAttributes() {
        List<HouseholdAttribute> attributes = Arrays.stream(HouseholdAttribute.values())
                .filter(attribute -> conditionCodes.contains(attribute.jaCode()))
                .toList();
        return attributes.isEmpty() ? null : attributes;
    }
}
