package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.constant.EscalationType;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EscalationQueryService {

    private final FactEscalationRepository factEscalationRepository;

    public Page<FactEscalation> getEscalations(
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        LocalDateTime from = safeStartDate.atStartOfDay();
        LocalDateTime to = safeEndDate.plusDays(1).atStartOfDay(); // exclusive upper bound

        Specification<FactEscalation> spec = Specification.where(tenantIdEquals(tenantId))
                .and(userIdEquals(userId))
                .and(escalationTypeEquals(normalizeTypeFilter(escalationType)))
                .and(schemeIdEquals(schemeId))
                .and(createdAtInRange(from, to));

        return factEscalationRepository.findAll(spec, pageable);
    }

    private static Specification<FactEscalation> tenantIdEquals(Integer tenantId) {
        if (tenantId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    private static Specification<FactEscalation> userIdEquals(Integer userId) {
        if (userId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    private static Specification<FactEscalation> escalationTypeEquals(String escalationType) {
        if (escalationType == null || escalationType.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get("escalationType"), escalationType);
    }

    private static Specification<FactEscalation> schemeIdEquals(Integer schemeId) {
        if (schemeId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("schemeId"), schemeId);
    }

    private static Specification<FactEscalation> createdAtInRange(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive),
                cb.lessThan(root.get("createdAt"), toExclusive)
        );
    }

    private static String normalizeTypeFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.matches("^\\d+$")) {
            String mapped = EscalationType.toDbType(Integer.parseInt(trimmed));
            return mapped != null ? mapped : trimmed;
        }
        return EscalationType.normalizeDbType(trimmed);
    }
}
