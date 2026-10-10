package kr.govbenefit.account.domain;

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
 * 회원 계정. id는 액세스 토큰의 {@code sub}다(결정 0014).
 */
@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link Emails#normalize}로 바꾼 값 */
    private String email;

    /** 접두사를 포함한 해시. 원문은 저장하지 않는다(결정 0013). */
    private String passwordHash;

    private LocalDateTime createdAt;

    public static UserAccount create(String email, String passwordHash, LocalDateTime now) {
        UserAccount account = new UserAccount();
        account.email = email;
        account.passwordHash = passwordHash;
        account.createdAt = now;
        return account;
    }
}
