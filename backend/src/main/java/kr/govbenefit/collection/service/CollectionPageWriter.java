package kr.govbenefit.collection.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitDetail;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitDetailRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.domain.CollectionQuarantine;
import kr.govbenefit.collection.domain.PageResult;
import kr.govbenefit.collection.domain.QuarantineReason;
import kr.govbenefit.collection.domain.SourceEndpoint;
import kr.govbenefit.collection.domain.SourceRecord;
import kr.govbenefit.collection.repository.CollectionQuarantineRepository;
import kr.govbenefit.collection.repository.SourceRecordHistoryRepository;
import kr.govbenefit.collection.repository.SourceRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받은 한 쪽을 한 트랜잭션으로 저장한다. 같은 쪽을 다시 저장해도 행이 늘거나 내용이 바뀌지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CollectionPageWriter {

    private final SourceRecordRepository sourceRecordRepository;
    private final SourceRecordHistoryRepository sourceRecordHistoryRepository;
    private final CollectionQuarantineRepository quarantineRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitDetailRepository benefitDetailRepository;
    private final ContentHasher contentHasher;
    private final JsonMapper jsonMapper;

    @Transactional
    public PageResult write(Long runId, SourceEndpoint endpoint, int page, List<JsonNode> records,
                            LocalDateTime now) {
        // 쪽 안에서 같은 서비스ID가 반복되면 마지막 레코드를 쓴다.
        Map<String, JsonNode> byServiceId = new LinkedHashMap<>();
        int quarantined = 0;
        for (JsonNode record : records) {
            String serviceId = Gov24RecordMapper.serviceId(record);
            if (serviceId == null) {
                quarantineRepository.save(CollectionQuarantine.of(runId, endpoint, page,
                        QuarantineReason.MISSING_SERVICE_ID, jsonMapper.writeValueAsString(record), now));
                quarantined++;
            } else {
                byServiceId.put(serviceId, record);
            }
        }

        SourceCounts counts = saveSourceRecords(runId, endpoint, byServiceId, now);
        int unlinked = switch (endpoint) {
            case SERVICE_LIST -> saveBenefits(byServiceId, now);
            case SERVICE_DETAIL -> saveBenefitDetails(byServiceId, now);
            case SUPPORT_CONDITIONS -> countUnlinked(byServiceId);
        };
        return new PageResult(records.size(), counts.created, counts.changed, counts.unchanged, quarantined,
                unlinked);
    }

    private SourceCounts saveSourceRecords(Long runId, SourceEndpoint endpoint, Map<String, JsonNode> records,
                                           LocalDateTime now) {
        Map<String, SourceRecord> existing = sourceRecordRepository
                .findAllByEndpointAndServiceIdIn(endpoint, records.keySet()).stream()
                .collect(Collectors.toMap(SourceRecord::getServiceId, Function.identity()));
        SourceCounts counts = new SourceCounts();
        List<SourceRecord> created = new ArrayList<>();
        records.forEach((serviceId, record) -> {
            String payload = jsonMapper.writeValueAsString(record);
            String hash = contentHasher.hash(record);
            SourceRecord current = existing.get(serviceId);
            if (current == null) {
                created.add(SourceRecord.create(serviceId, endpoint, payload, hash, now));
                counts.created++;
            } else if (!current.hasSameContent(hash)) {
                sourceRecordHistoryRepository.save(current.replaceContent(payload, hash, runId, now));
                counts.changed++;
            } else {
                // DB의 JSON 컬럼은 키 순서·공백을 바꿔 돌려주므로 문자열이 아니라 트리로 비교한다.
                current.confirm(payload, !jsonMapper.readTree(current.getPayload()).equals(record), now);
                counts.unchanged++;
            }
        });
        sourceRecordRepository.saveAll(created);
        return counts;
    }

    private int saveBenefits(Map<String, JsonNode> records, LocalDateTime now) {
        Map<String, Benefit> existing = findBenefits(records);
        List<Benefit> created = new ArrayList<>();
        records.forEach((serviceId, record) -> {
            Benefit benefit = existing.get(serviceId);
            BenefitContent content = Gov24RecordMapper.toBenefitContent(record);
            BenefitNormalization normalization = Gov24RecordMapper.toBenefitNormalization(record);
            if (benefit == null) {
                created.add(Benefit.create(serviceId, content, normalization, now));
            } else {
                benefit.confirm(content, normalization, now);
            }
        });
        benefitRepository.saveAll(created);
        return 0;
    }

    private int saveBenefitDetails(Map<String, JsonNode> records, LocalDateTime now) {
        Map<String, Benefit> benefits = findBenefits(records);
        Map<Long, BenefitDetail> existing = benefitDetailRepository
                .findAllByBenefitIdIn(benefits.values().stream().map(Benefit::getId).toList()).stream()
                .collect(Collectors.toMap(BenefitDetail::getBenefitId, Function.identity()));
        List<BenefitDetail> created = new ArrayList<>();
        int unlinked = 0;
        for (Map.Entry<String, JsonNode> entry : records.entrySet()) {
            Benefit benefit = benefits.get(entry.getKey());
            if (benefit == null) {
                // 목록에 없는 서비스ID는 원문만 남긴다. 목록에 나타난 뒤의 수집에서 연결된다.
                unlinked++;
                continue;
            }
            BenefitDetail detail = existing.get(benefit.getId());
            if (detail == null) {
                created.add(BenefitDetail.create(benefit.getId(),
                        Gov24RecordMapper.toBenefitDetailContent(entry.getValue()), now));
            } else {
                detail.update(Gov24RecordMapper.toBenefitDetailContent(entry.getValue()), now);
            }
        }
        benefitDetailRepository.saveAll(created);
        return unlinked;
    }

    /**
     * 지원조건은 원문으로만 저장한다. 목록에 없는 서비스ID만 센다.
     */
    private int countUnlinked(Map<String, JsonNode> records) {
        return records.size() - findBenefits(records).size();
    }

    private Map<String, Benefit> findBenefits(Map<String, JsonNode> records) {
        return benefitRepository.findAllByServiceIdIn(records.keySet()).stream()
                .collect(Collectors.toMap(Benefit::getServiceId, Function.identity()));
    }

    private static final class SourceCounts {
        private int created;
        private int changed;
        private int unchanged;
    }
}
