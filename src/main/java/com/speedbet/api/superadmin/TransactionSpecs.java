package com.speedbet.api.superadmin;

import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TxKind;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TransactionSpecs {

    // NOTE: walletIdIn was added to fix the Railway build (it was called by
    // SuperAdminQueryService but missing). This comment re-triggers a fresh
    // Railway deployment of the fixed tree — no functional change.
    private TransactionSpecs() {}

    /**
     * Build a Specification that filters transactions by any combination of:
     *   kind, status, walletId, createdAt range.
     * Null / empty parameters are simply omitted from the WHERE clause.
     */
    /**
     * Restrict transactions to a set of wallets. An empty / null set matches
     * nothing (never everything) — a fresh super admin with no branch yet
     * sees an empty list, not the whole platform.
     */
    public static Specification<Transaction> walletIdIn(
            java.util.Collection<UUID> walletIds) {
        return (root, query, cb) -> {
            if (walletIds == null || walletIds.isEmpty()) {
                return cb.disjunction();
            }
            return root.get("walletId").in(walletIds);
        };
    }

    public static Specification<Transaction> filtered(
            TxKind kind,
            String status,
            UUID   walletId,
            Instant from,
            Instant to) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (kind != null) {
                predicates.add(cb.equal(root.get("kind"), kind));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (walletId != null) {
                predicates.add(cb.equal(root.get("walletId"), walletId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}