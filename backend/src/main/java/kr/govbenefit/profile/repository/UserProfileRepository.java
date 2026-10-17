package kr.govbenefit.profile.repository;

import kr.govbenefit.profile.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
}
