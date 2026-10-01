package kr.govbenefit.benefit.dto;

import kr.govbenefit.benefit.domain.DetailStatus;

public record BenefitView(String serviceId, String name, DetailStatus detailStatus) {
}
