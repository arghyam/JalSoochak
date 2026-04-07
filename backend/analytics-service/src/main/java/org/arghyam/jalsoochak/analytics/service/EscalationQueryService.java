package org.arghyam.jalsoochak.analytics.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EscalationQueryService {

    private final EntityManager em;

    public Page<EscalationListItemDto> getEscalations(
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            String schemeName,
            Integer resolutionStatus,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        LocalDateTime from = safeStartDate.atStartOfDay();
        LocalDateTime to = safeEndDate.plusDays(1).atStartOfDay();

        String escalationTypeFilter = (escalationType == null || escalationType.isBlank())
                ? null
                : escalationType.trim();

        String schemeNameFilter = (schemeName == null || schemeName.isBlank())
                ? null
                : schemeName.trim();

        CriteriaBuilder cb = em.getCriteriaBuilder();

        // "Left join" semantics:
        // - If scheme_name filter is provided, we must join dim_scheme_table to filter, which is inner-join-like.
        // - If scheme_name filter is NOT provided, we should not depend on dim_scheme_table; we enrich names
        //   best-effort (scheme_name may be null if missing in dim_scheme_table).
        if (schemeNameFilter == null) {
            return getEscalationsWithoutSchemeJoin(cb, tenantId, userId, escalationTypeFilter, schemeId, resolutionStatus, from, to, pageable);
        }

        return getEscalationsWithSchemeNameFilter(cb, tenantId, userId, escalationTypeFilter, schemeId, schemeNameFilter, resolutionStatus, from, to, pageable);
    }

    private Page<EscalationListItemDto> getEscalationsWithoutSchemeJoin(
            CriteriaBuilder cb,
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            Integer resolutionStatus,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Pageable pageable
    ) {
        CriteriaQuery<FactEscalation> dataQuery = cb.createQuery(FactEscalation.class);
        Root<FactEscalation> e = dataQuery.from(FactEscalation.class);
        dataQuery.select(e);
        dataQuery.where(buildPredicatesNoScheme(cb, e, tenantId, userId, escalationType, schemeId, resolutionStatus, fromInclusive, toExclusive));
        dataQuery.orderBy(buildOrders(cb, e, pageable));

        TypedQuery<FactEscalation> typed = em.createQuery(dataQuery);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<FactEscalation> rows = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<FactEscalation> e2 = countQuery.from(FactEscalation.class);
        countQuery.select(cb.count(e2.get("id")));
        countQuery.where(buildPredicatesNoScheme(cb, e2, tenantId, userId, escalationType, schemeId, resolutionStatus, fromInclusive, toExclusive));
        Long total = em.createQuery(countQuery).getSingleResult();

        Map<Integer, String> schemeNames = loadSchemeNames(rows);
        List<EscalationListItemDto> content = rows.stream()
                .map(r -> EscalationListItemDto.builder()
                        .id(r.getId())
                        .tenantId(r.getTenantId())
                        .schemeId(r.getSchemeId())
                        .escalationType(r.getEscalationType())
                        .message(r.getMessage())
                        .correlationId(r.getCorrelationId())
                        .userId(r.getUserId())
                        .resolutionStatusCode(r.getResolutionStatus())
                        .remark(r.getRemark())
                        .createdAt(r.getCreatedAt())
                        .updatedAt(r.getUpdatedAt())
                        .schemeName(schemeNames.get(r.getSchemeId()))
                        .build())
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    private Page<EscalationListItemDto> getEscalationsWithSchemeNameFilter(
            CriteriaBuilder cb,
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            String schemeName,
            Integer resolutionStatus,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Pageable pageable
    ) {
        CriteriaQuery<EscalationListItemDto> dataQuery = cb.createQuery(EscalationListItemDto.class);
        Root<FactEscalation> e = dataQuery.from(FactEscalation.class);
        Root<DimScheme> s = dataQuery.from(DimScheme.class);

        Predicate[] predicates = buildPredicatesWithScheme(cb, e, s, tenantId, userId, escalationType, schemeId, schemeName, resolutionStatus, fromInclusive, toExclusive);
        dataQuery.select(cb.construct(
                EscalationListItemDto.class,
                e.get("id"),
                e.get("tenantId"),
                e.get("schemeId"),
                e.get("escalationType"),
                e.get("message"),
                e.get("correlationId"),
                e.get("userId"),
                e.get("resolutionStatus"),
                e.get("remark"),
                e.get("createdAt"),
                e.get("updatedAt"),
                s.get("schemeName")
        ));
        dataQuery.where(predicates);
        dataQuery.orderBy(buildOrders(cb, e, pageable));

        TypedQuery<EscalationListItemDto> typed = em.createQuery(dataQuery);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<EscalationListItemDto> content = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<FactEscalation> e2 = countQuery.from(FactEscalation.class);
        Root<DimScheme> s2 = countQuery.from(DimScheme.class);
        countQuery.select(cb.count(e2.get("id")));
        countQuery.where(buildPredicatesWithScheme(cb, e2, s2, tenantId, userId, escalationType, schemeId, schemeName, resolutionStatus, fromInclusive, toExclusive));

        Long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private Map<Integer, String> loadSchemeNames(List<FactEscalation> rows) {
        Set<Integer> schemeIds = rows.stream()
                .map(FactEscalation::getSchemeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (schemeIds.isEmpty()) {
            return Map.of();
        }

        List<Object[]> pairs = em.createQuery(
                        "select s.schemeId, s.schemeName from DimScheme s where s.schemeId in :ids",
                        Object[].class
                )
                .setParameter("ids", schemeIds)
                .getResultList();

        Map<Integer, String> map = new HashMap<>();
        for (Object[] p : pairs) {
            Integer id = (Integer) p[0];
            String name = (String) p[1];
            map.put(id, name);
        }
        return map;
    }

    private static Predicate[] buildPredicatesNoScheme(
            CriteriaBuilder cb,
            Root<FactEscalation> e,
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            Integer resolutionStatus,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
        List<Predicate> p = new ArrayList<>();
        if (tenantId != null) {
            p.add(cb.equal(e.get("tenantId"), tenantId));
        }
        if (userId != null) {
            p.add(cb.equal(e.get("userId"), userId));
        }
        if (escalationType != null) {
            p.add(cb.equal(e.get("escalationType"), escalationType));
        }
        if (schemeId != null) {
            p.add(cb.equal(e.get("schemeId"), schemeId));
        }
        if (resolutionStatus != null) {
            p.add(cb.equal(e.get("resolutionStatus"), resolutionStatus));
        }
        p.add(cb.greaterThanOrEqualTo(e.get("createdAt"), fromInclusive));
        p.add(cb.lessThan(e.get("createdAt"), toExclusive));
        return p.toArray(Predicate[]::new);
    }

    private static Predicate[] buildPredicatesWithScheme(
            CriteriaBuilder cb,
            Root<FactEscalation> e,
            Root<DimScheme> s,
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            String schemeName,
            Integer resolutionStatus,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(e.get("schemeId"), s.get("schemeId")));
        if (tenantId != null) {
            p.add(cb.equal(e.get("tenantId"), tenantId));
        }
        if (userId != null) {
            p.add(cb.equal(e.get("userId"), userId));
        }
        if (escalationType != null) {
            p.add(cb.equal(e.get("escalationType"), escalationType));
        }
        if (schemeId != null) {
            p.add(cb.equal(e.get("schemeId"), schemeId));
        }
        if (resolutionStatus != null) {
            p.add(cb.equal(e.get("resolutionStatus"), resolutionStatus));
        }
        p.add(cb.like(
                cb.lower(s.get("schemeName")),
                "%" + schemeName.toLowerCase() + "%"
        ));
        p.add(cb.greaterThanOrEqualTo(e.get("createdAt"), fromInclusive));
        p.add(cb.lessThan(e.get("createdAt"), toExclusive));
        return p.toArray(Predicate[]::new);
    }

    private static List<Order> buildOrders(CriteriaBuilder cb, Root<FactEscalation> e, Pageable pageable) {
        List<Order> orders = new ArrayList<>();
        if (pageable.getSort().isSorted()) {
            for (Sort.Order o : pageable.getSort()) {
                if ("createdAt".equals(o.getProperty())) {
                    orders.add(o.isAscending() ? cb.asc(e.get("createdAt")) : cb.desc(e.get("createdAt")));
                }
            }
        }
        if (orders.isEmpty()) {
            orders.add(cb.desc(e.get("createdAt")));
        }
        return orders;
    }
}
