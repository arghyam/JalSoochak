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
import java.util.List;

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

        CriteriaQuery<EscalationListItemDto> dataQuery = cb.createQuery(EscalationListItemDto.class);
        Root<FactEscalation> e = dataQuery.from(FactEscalation.class);
        Root<DimScheme> s = dataQuery.from(DimScheme.class);

        Predicate[] predicates = buildPredicates(cb, e, s, tenantId, userId, escalationTypeFilter, schemeId, schemeNameFilter, from, to);
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
        countQuery.where(buildPredicates(cb, e2, s2, tenantId, userId, escalationTypeFilter, schemeId, schemeNameFilter, from, to));

        Long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private static Predicate[] buildPredicates(
            CriteriaBuilder cb,
            Root<FactEscalation> e,
            Root<DimScheme> s,
            Integer tenantId,
            Integer userId,
            String escalationType,
            Integer schemeId,
            String schemeName,
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
        if (schemeName != null) {
            p.add(cb.like(
                    cb.lower(s.get("schemeName")),
                    "%" + schemeName.toLowerCase() + "%"
            ));
        }
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
