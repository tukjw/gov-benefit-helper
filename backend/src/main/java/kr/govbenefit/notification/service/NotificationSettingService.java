package kr.govbenefit.notification.service;

import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.common.api.BusinessException;
import kr.govbenefit.common.api.ErrorCode;
import kr.govbenefit.notification.dto.NotificationSettingRequest;
import kr.govbenefit.notification.dto.NotificationSettingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 혜택 알림 켜기·끄기(REQ-NOTIFY-03, DEC-NOTIFY). 관심 저장과 별개다. 사용자는 토큰으로만 정하고(결정 0018), 계정이 없으면
 * (탈퇴 뒤 남은 토큰) {@code UNAUTHENTICATED}다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final UserAccountRepository userAccountRepository;

    @Transactional(readOnly = true)
    public NotificationSettingResponse get(Long userId) {
        return NotificationSettingResponse.from(account(userId));
    }

    @Transactional
    public void update(Long userId, NotificationSettingRequest request) {
        account(userId).changeNewBenefitNotify(request.newBenefit());
    }

    private UserAccount account(Long userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
