package kr.govbenefit.collection.service;

import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitDetailContent;
import tools.jackson.databind.JsonNode;

/**
 * 원문 레코드의 한글 필드를 혜택 항목으로 옮긴다. 값은 해석하지 않고 문자열 그대로 둔다.
 */
final class Gov24RecordMapper {

    static final String SERVICE_ID = "서비스ID";

    private Gov24RecordMapper() {
    }

    /**
     * 필수 식별자. 없거나, 문자열이 아니거나, 공백이면 null이다.
     */
    static String serviceId(JsonNode record) {
        if (record == null || !record.isObject()) {
            return null;
        }
        JsonNode value = record.get(SERVICE_ID);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return null;
        }
        return value.stringValue();
    }

    static BenefitContent toBenefitContent(JsonNode record) {
        return new BenefitContent(
                text(record, "서비스명"),
                text(record, "서비스목적요약"),
                text(record, "서비스분야"),
                text(record, "사용자구분"),
                text(record, "지원유형"),
                text(record, "지원대상"),
                text(record, "선정기준"),
                text(record, "지원내용"),
                text(record, "신청기한"),
                text(record, "신청방법"),
                text(record, "전화문의"),
                text(record, "접수기관"),
                text(record, "소관기관명"),
                text(record, "소관기관유형"),
                text(record, "소관기관코드"),
                text(record, "부서명"),
                text(record, "상세조회URL"));
    }

    static BenefitDetailContent toBenefitDetailContent(JsonNode record) {
        return new BenefitDetailContent(
                text(record, "서비스목적"),
                text(record, "신청방법"),
                text(record, "구비서류"),
                text(record, "공무원확인구비서류"),
                text(record, "본인확인필요구비서류"),
                text(record, "문의처"),
                text(record, "온라인신청사이트URL"),
                text(record, "법령"),
                text(record, "자치법규"),
                text(record, "행정규칙"),
                text(record, "접수기관명"),
                text(record, "지원유형"));
    }

    private static String text(JsonNode record, String field) {
        JsonNode value = record.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.toString();
    }
}
