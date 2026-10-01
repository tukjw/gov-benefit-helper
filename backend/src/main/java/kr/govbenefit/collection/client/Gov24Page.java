package kr.govbenefit.collection.client;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 공공서비스 API 응답 한 쪽.
 *
 * @param totalCount 경로의 전체 건수
 * @param data       레코드. 마지막 쪽 뒤에서는 비어 있다
 */
public record Gov24Page(int totalCount, List<JsonNode> data) {
}
