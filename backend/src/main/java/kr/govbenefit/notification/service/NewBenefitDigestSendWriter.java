package kr.govbenefit.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.mail.AccountMailSendException;
import kr.govbenefit.account.mail.NewBenefitMailItem;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.notification.config.NewBenefitSendProperties;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 묶음 하나의 발송 준비와 결과 기록을 각각 한 트랜잭션으로 한다(결정 0048). SES 호출은 두 트랜잭션 밖에서 하므로, 응답을
 * 기다리는 동안 DB 연결을 잡지 않는다. {@link NewBenefitDigestSender}만 부른다.
 *
 * <p>그사이 탈퇴해 묶음이 지워졌으면 준비도 기록도 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class NewBenefitDigestSendWriter {

    private final NewBenefitDigestRepository digestRepository;
    private final NewBenefitTargetRepository targetRepository;
    private final UserAccountRepository userAccountRepository;
    private final NewBenefitSendProperties properties;

    /**
     * 보내기 직전의 확인. 묶은 날이 지났으면 최종 실패로, 알림이 꺼져 있으면 취소로 끝낸다.
     *
     * @return 보낼 메일. 보내지 않고 끝냈으면 비어 있다.
     */
    @Transactional
    public Optional<NewBenefitDigestMail> prepare(Long digestId, LocalDateTime now) {
        Optional<NewBenefitDigest> found = digestRepository.findById(digestId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NewBenefitDigest digest = found.get();
        if (digest.isExpired(now)) {
            digest.expire();
            return Optional.empty();
        }
        UserAccount account = userAccountRepository.findById(digest.getUserId()).orElseThrow();
        if (!account.isNewBenefitNotify()) {
            digest.cancel();
            return Optional.empty();
        }
        List<NewBenefitMailItem> benefits = targetRepository.findDigestBenefits(digestId).stream()
                .map(benefit -> new NewBenefitMailItem(benefit.getName(), benefit.getAgencyName(),
                        properties.siteUrl() + "/benefits/" + benefit.getServiceId()))
                .toList();
        return Optional.of(new NewBenefitDigestMail(account.getEmail(), benefits));
    }

    @Transactional
    public void accept(Long digestId, LocalDateTime now) {
        digestRepository.findById(digestId).ifPresent(digest -> digest.accept(now));
    }

    /**
     * @return 기록한 묶음. 시도 횟수와 상태를 로그에 남길 때 쓴다.
     */
    @Transactional
    public Optional<NewBenefitDigest> fail(Long digestId, AccountMailSendException e, LocalDateTime now) {
        Optional<NewBenefitDigest> found = digestRepository.findById(digestId);
        found.ifPresent(digest -> digest.fail(e.getMessage(), e.isRetryable(), now, properties.retryDelays()));
        return found;
    }

    /**
     * @param benefits 혜택 ID 순
     */
    public record NewBenefitDigestMail(String email, List<NewBenefitMailItem> benefits) {
    }
}
