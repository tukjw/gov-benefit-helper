package kr.govbenefit.bookmark.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.dto.BenefitSummary;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.bookmark.domain.Bookmark;
import kr.govbenefit.bookmark.dto.BookmarkItem;
import kr.govbenefit.bookmark.dto.BookmarkListResponse;
import kr.govbenefit.bookmark.dto.BookmarkSavedResponse;
import kr.govbenefit.bookmark.repository.BookmarkRepository;
import kr.govbenefit.common.api.ApiTimes;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 북마크 저장·해제·목록(REQ-SAVE-01의 관심 목록). 사용자는 토큰으로만 정하고(결정 0018), 혜택은 서비스 ID로 가리킨다(DEC-SEARCH).
 * 계정이 없으면(탈퇴 뒤 남은 토큰) 모두 {@code UNAUTHENTICATED}다.
 * 한계: 저장할 때 계정을 잠그지 않으므로, 계정 확인 뒤 INSERT 전에 탈퇴가 커밋되면 FK 위반으로 500이 된다. 데이터는 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    /** 마지막으로 저장한 순서. 같은 시각이면 혜택 ID 역순이라 순서가 하나로 정해진다. */
    private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("bookmarkedAt"), Sort.Order.desc("benefitId"));

    private final BookmarkRepository bookmarkRepository;
    private final BenefitRepository benefitRepository;
    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    /**
     * 처음이면 만들고, 이미 있으면 저장 시각만 바꾼다.
     *
     * @throws BusinessException 수집되지 않은 서비스 ID다({@code NOT_FOUND}).
     */
    @Transactional
    public void save(Long userId, String serviceId) {
        requireAccount(userId);
        Benefit benefit = benefitRepository.findByServiceId(serviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "혜택이 없습니다. serviceId=" + serviceId));
        bookmarkRepository.upsert(userId, benefit.getId(), LocalDateTime.now(clock));
    }

    /**
     * 저장하지 않았거나 수집되지 않은 서비스 ID여도 미저장 상태로 끝나므로 오류가 아니다.
     */
    @Transactional
    public void remove(Long userId, String serviceId) {
        requireAccount(userId);
        benefitRepository.findByServiceId(serviceId)
                .ifPresent(benefit -> bookmarkRepository.deleteByUserIdAndBenefitId(userId, benefit.getId()));
    }

    /**
     * 북마크 한 쪽과 혜택의 현재 정보. 북마크와 혜택을 한 읽기 트랜잭션에서 같은 스냅샷으로 읽는다.
     *
     * @param page 1부터 센 쪽 번호
     */
    @Transactional(readOnly = true)
    public BookmarkListResponse list(Long userId, int page, int size) {
        requireAccount(userId);
        if ((long) (page - 1) * size > Integer.MAX_VALUE) {
            // JPA의 시작 위치는 int라 넘길 수 없다. 건수도 int 범위이므로 이 쪽은 항상 마지막 쪽을 넘는다.
            return new BookmarkListResponse(bookmarkRepository.countByUserId(userId), page, size, List.of());
        }
        Page<Bookmark> bookmarks = bookmarkRepository.findByUserId(userId, PageRequest.of(page - 1, size, LIST_ORDER));
        // 혜택 행은 지워지지 않으므로(결정 0023) 모든 북마크에 혜택이 있다.
        Map<Long, Benefit> benefits = benefitRepository.findAllById(
                        bookmarks.getContent().stream().map(Bookmark::getBenefitId).toList())
                .stream().collect(Collectors.toMap(Benefit::getId, Function.identity()));
        List<BookmarkItem> items = bookmarks.getContent().stream()
                .map(bookmark -> new BookmarkItem(ApiTimes.toOffset(bookmark.getBookmarkedAt()),
                        BenefitSummary.from(benefits.get(bookmark.getBenefitId()))))
                .toList();
        return new BookmarkListResponse(bookmarks.getTotalElements(), page, size, items);
    }

    /**
     * 받은 서비스 ID 중 북마크에 있는 것. 수집되지 않은 서비스 ID는 북마크에 있을 수 없으므로 빠진다.
     *
     * @return 요청 순서이며 중복이 없다.
     */
    @Transactional(readOnly = true)
    public BookmarkSavedResponse savedServiceIds(Long userId, List<String> serviceIds) {
        requireAccount(userId);
        Set<String> requested = new LinkedHashSet<>(serviceIds);
        Set<String> saved = new HashSet<>(bookmarkRepository.findSavedServiceIds(userId, requested));
        return new BookmarkSavedResponse(requested.stream().filter(saved::contains).toList());
    }

    private void requireAccount(Long userId) {
        if (!userAccountRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
