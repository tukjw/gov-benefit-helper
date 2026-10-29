package kr.govbenefit.bookmark.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 북마크(REQ-SAVE-01의 관심 목록). 사용자·혜택 조합당 하나다. 쓰기는 저장소의 네이티브 쿼리로만 하므로 생성 메서드가 없다.
 */
@Entity
@Table(name = "bookmark")
@IdClass(Bookmark.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bookmark {

    /** 계정 ID. 계정이 지워지면 함께 지워진다. */
    @Id
    private Long userId;

    /** 혜택 ID. 북마크가 달린 혜택 행은 지울 수 없다(결정 0023). */
    @Id
    private Long benefitId;

    /** 마지막으로 저장한 시각. 다시 저장하면 바뀐다. */
    private LocalDateTime bookmarkedAt;

    public record Key(Long userId, Long benefitId) implements Serializable {
    }
}
