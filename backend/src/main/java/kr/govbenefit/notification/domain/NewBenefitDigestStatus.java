package kr.govbenefit.notification.domain;

/**
 * 묶음의 발송 상태(결정 0048). SES가 접수한 것만 알고, 실제 전달과 읽음은 알지 못한다.
 */
public enum NewBenefitDigestStatus {
    /** 아직 보내지 않았거나, 일시 실패 뒤 다시 보낼 차례를 기다린다. */
    PENDING,
    /** SES가 접수했다. */
    ACCEPTED,
    /** 보내지 못하고 끝났다. 재시도 횟수를 다 썼거나, 다시 보내도 안 되는 실패였거나, 묶은 날이 지났다. */
    FAILED,
    /** 보내기 직전에 알림이 꺼져 있어 보내지 않았다. */
    CANCELED
}
