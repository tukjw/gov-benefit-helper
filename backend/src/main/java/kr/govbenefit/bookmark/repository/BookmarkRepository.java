package kr.govbenefit.bookmark.repository;

import java.time.LocalDateTime;
import kr.govbenefit.bookmark.domain.Bookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookmarkRepository extends JpaRepository<Bookmark, Bookmark.Key> {

    /**
     * 북마크를 만들거나, 이미 있으면 저장 시각만 바꾼다. 한 문장이라 동시에 저장해도 행이 하나다.
     * FK 위반을 경고로 삼키지 않도록 {@code INSERT IGNORE}를 쓰지 않는다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO bookmark (user_id, benefit_id, bookmarked_at) VALUES (:userId, :benefitId, :bookmarkedAt) AS new
            ON DUPLICATE KEY UPDATE bookmarked_at = new.bookmarked_at""", nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("benefitId") Long benefitId,
               @Param("bookmarkedAt") LocalDateTime bookmarkedAt);

    @Modifying
    @Query("delete from Bookmark b where b.userId = :userId and b.benefitId = :benefitId")
    int deleteByUserIdAndBenefitId(@Param("userId") Long userId, @Param("benefitId") Long benefitId);

    Page<Bookmark> findByUserId(Long userId, Pageable pageable);

    long countByUserId(Long userId);
}
