package kr.govbenefit.profile.repository;

import java.util.Optional;
import kr.govbenefit.profile.domain.UserProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /** 속성 코드까지 한 번에 읽어, 트랜잭션 밖에서도 속성을 볼 수 있다. */
    @EntityGraph(attributePaths = "conditionCodes")
    Optional<UserProfile> findWithConditionCodesByUserId(Long userId);
}
