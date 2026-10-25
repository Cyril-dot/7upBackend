package com.speedbet.api.affiliate;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Super-admin commission payouts: SA requests, owner pays, plus
 * pay-all and the daily auto-settle.
 *
 * Money model (same as the rest of the commission system): balances are
 * GHS-denominated and live in {@link AffiliateCommissionBalance}, totally
 * separate from the main wallet. A payout request snapshots the balance;
 * approval moves nothing; mark-paid debits exactly the snapshot amount
 * and is the owner's confirmation that the off-platform transfer was sent.
 * Pay-all / auto-settle skip any SA with an open request so their money
 * is never cleared twice through two paths.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminPayoutService {

    private final SuperAdminPayoutRequestRepository requestRepo;
    private final SuperAdminPayoutSettingsRepository settingsRepo;
    private final AffiliateCommissionBalanceRepository commissionRepo;
    private final AffiliateCommissionService commissionService;
    private final UserRepository userRepo;
    private final AuditService auditService;

    private static final List<PayoutStatus> OPEN = List.of(PayoutStatus.REQUESTED, PayoutStatus.APPROVED);

    // ─── Super-admin side ────────────────────────────────────────────────

    @Transactional
    public SuperAdminPayoutRequest requestPayout(UUID superAdminId) {
        if (requestRepo.existsBySuperAdminIdAndStatusIn(superAdminId, OPEN)) {
            throw ApiException.badRequest(
                    "You already have a pending payout request. "
                            + "Please wait for it to be resolved before submitting a new one.");
        }
        var balanceRow = commissionRepo.findByUserId(superAdminId).orElse(null);
        BigDecimal balance = balanceRow == null ? BigDecimal.ZERO : balanceRow.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("No commission balance available to request a payout.");
        }
        var saved = requestRepo.save(SuperAdminPayoutRequest.builder()
                .superAdminId(superAdminId)
                .amount(balance)
                .currency(balanceRow.getCurrency())
                .status(PayoutStatus.REQUESTED)
                .requestedAt(Instant.now())
                .build());
        auditService.log(superAdminId, "REQUEST_SA_PAYOUT", "super_admin_payout_requests", saved.getId(),
                null, Map.of("amount", balance.toPlainString(), "currency", saved.getCurrency()), null);
        log.info("requestPayout: saId={} amount={} {}", superAdminId, balance, saved.getCurrency());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SuperAdminPayoutRequest> listOwn(UUID superAdminId) {
        return requestRepo.findBySuperAdminIdOrderByCreatedAtDesc(superAdminId);
    }

    /** Balance snapshot for the SA panel. Read-only: never creates a balance row. */
    @Transactional(readOnly = true)
    public Map<String, Object> balanceOf(UUID superAdminId, BigDecimal rate) {
        var row = commissionRepo.findByUserId(superAdminId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", row == null ? BigDecimal.ZERO : row.getBalance());
        out.put("currency", row == null ? "GHS" : row.getCurrency());
        out.put("totalEarnedLifetime", row == null ? BigDecimal.ZERO : row.getTotalEarnedLifetime());
        out.put("totalPaidOutLifetime", row == null ? BigDecimal.ZERO : row.getTotalPaidOutLifetime());
        out.put("hasPendingRequest", requestRepo.existsBySuperAdminIdAndStatusIn(superAdminId, OPEN));
        out.put("commissionRate", rate);
        return out;
    }

    // ─── Site-owner side ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForOwner(String status) {
        List<SuperAdminPayoutRequest> rows;
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            rows = requestRepo.findAllByOrderByCreatedAtDesc();
        } else {
            PayoutStatus parsed;
            try {
                parsed = PayoutStatus.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown payout status: " + status);
            }
            rows = requestRepo.findByStatusOrderByCreatedAtDesc(parsed);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            var sa = userRepo.findById(r.getSuperAdminId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("superAdminId", r.getSuperAdminId());
            m.put("superAdminEmail", sa == null ? "unknown" : sa.getEmail());
            m.put("superAdminName", sa == null ? "" : ((sa.getFirstName() == null ? "" : sa.getFirstName()) + " " + (sa.getLastName() == null ? "" : sa.getLastName())).trim());
            m.put("amount", r.getAmount());
            m.put("currency", r.getCurrency());
            m.put("status", r.getStatus().name());
            m.put("requestedAt", r.getRequestedAt());
            m.put("paidAt", r.getPaidAt());
            m.put("rejectReason", r.getRejectReason());
            m.put("createdAt", r.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    @Transactional
    public SuperAdminPayoutRequest approve(UUID id, UUID ownerId) {
        var req = get(id);
        if (req.getStatus() != PayoutStatus.REQUESTED) {
            throw ApiException.badRequest("Only a REQUESTED payout can be approved.");
        }
        req.setStatus(PayoutStatus.APPROVED);
        var saved = requestRepo.save(req);
        auditService.log(ownerId, "APPROVE_SA_PAYOUT", "super_admin_payout_requests", saved.getId(),
                Map.of("status", "REQUESTED"), Map.of("status", "APPROVED"), null);
        return saved;
    }

    @Transactional
    public SuperAdminPayoutRequest reject(UUID id, UUID ownerId, String reason) {
        var req = get(id);
        if (req.getStatus() != PayoutStatus.REQUESTED) {
            throw ApiException.badRequest("Only a REQUESTED payout can be rejected.");
        }
        req.setStatus(PayoutStatus.REJECTED);
        req.setRejectReason(reason);
        var saved = requestRepo.save(req);
        auditService.log(ownerId, "REJECT_SA_PAYOUT", "super_admin_payout_requests", saved.getId(),
                Map.of("status", "REQUESTED"), Map.of("status", "REJECTED"), null);
        return saved;
    }

    /**
     * Owner confirms the off-platform transfer was sent. Debits exactly the
     * snapshotted amount; refuses (409) if the balance no longer covers it,
     * which is how a double-pay via the 01:00 affiliate sweep is prevented.
     */
    @Transactional
    public SuperAdminPayoutRequest markPaid(UUID id, UUID ownerId) {
        var req = get(id);
        if (req.getStatus() != PayoutStatus.APPROVED) {
            throw ApiException.badRequest("Only an APPROVED payout can be marked paid. Approve it first.");
        }
        commissionService.debitCommission(req.getSuperAdminId(), req.getAmount());
        req.setStatus(PayoutStatus.PAID);
        req.setPaidAt(Instant.now());
        var saved = requestRepo.save(req);
        auditService.log(ownerId, "MARK_SA_PAYOUT_PAID", "super_admin_payout_requests", saved.getId(),
                Map.of("status", "APPROVED"), Map.of("status", "PAID", "amount", req.getAmount().toPlainString()), null);
        log.info("markPaid: payoutId={} saId={} amount={}", id, req.getSuperAdminId(), req.getAmount());
        return saved;
    }

    /**
     * Pay every super admin's current balance in one run. SAs with an open
     * request are skipped (their money goes through that request, never
     * through both paths). Source is MANUAL (owner button) or AUTO
     * (daily scheduler); actorId is null for the scheduler, which skips
     * audit rows.
     */
    @Transactional
    public Map<String, Object> payAll(UUID actorId, String source) {
        int paidCount = 0;
        int skippedPending = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (var sa : userRepo.findAllByRole(UserRole.SUPER_ADMIN)) {
            if (requestRepo.existsBySuperAdminIdAndStatusIn(sa.getId(), OPEN)) {
                skippedPending++;
                continue;
            }
            var row = commissionRepo.findByUserId(sa.getId()).orElse(null);
            if (row == null || row.getBalance().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal amount = row.getBalance();
            commissionService.debitCommission(sa.getId(), amount);
            var saved = requestRepo.save(SuperAdminPayoutRequest.builder()
                    .superAdminId(sa.getId())
                    .amount(amount)
                    .currency(row.getCurrency())
                    .status(PayoutStatus.PAID)
                    .requestedAt(Instant.now())
                    .paidAt(Instant.now())
                    .build());
            if (actorId != null) {
                auditService.log(actorId, "PAY_ALL_SA_PAYOUT", "super_admin_payout_requests", saved.getId(),
                        null, Map.of("amount", amount.toPlainString(), "source", source), null);
            }
            paidCount++;
            total = total.add(amount);
        }
        log.info("payAll: source={} paidCount={} skippedPending={} total={}", source, paidCount, skippedPending, total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paidCount", paidCount);
        out.put("skippedPendingCount", skippedPending);
        out.put("totalPaid", total);
        out.put("currency", "GHS");
        out.put("source", source);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        BigDecimal totalOwed = BigDecimal.ZERO;
        BigDecimal paidLifetime = BigDecimal.ZERO;
        for (var sa : userRepo.findAllByRole(UserRole.SUPER_ADMIN)) {
            var row = commissionRepo.findByUserId(sa.getId()).orElse(null);
            if (row != null) {
                totalOwed = totalOwed.add(row.getBalance());
                paidLifetime = paidLifetime.add(row.getTotalPaidOutLifetime());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalOwed", totalOwed);
        out.put("pendingRequests", requestRepo.countByStatus(PayoutStatus.REQUESTED));
        out.put("approvedAwaitingPayment", requestRepo.countByStatus(PayoutStatus.APPROVED));
        out.put("paidLifetime", paidLifetime);
        out.put("autoSettleDaily", getSettings().isAutoSettleDaily());
        out.put("currency", "GHS");
        return out;
    }

    @Transactional
    public SuperAdminPayoutSettings getSettings() {
        return settingsRepo.findById(1L).orElseGet(() ->
                settingsRepo.save(SuperAdminPayoutSettings.builder().id(1L).autoSettleDaily(false).build()));
    }

    @Transactional
    public SuperAdminPayoutSettings setAutoSettle(boolean enabled, UUID ownerId) {
        var settings = getSettings();
        boolean before = settings.isAutoSettleDaily();
        settings.setAutoSettleDaily(enabled);
        settings.setUpdatedAt(Instant.now());
        var saved = settingsRepo.save(settings);
        auditService.log(ownerId, "SET_SA_PAYOUT_AUTO_SETTLE", "super_admin_payout_settings", null,
                Map.of("autoSettleDaily", before), Map.of("autoSettleDaily", enabled), null);
        return saved;
    }

    private SuperAdminPayoutRequest get(UUID id) {
        return requestRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Payout request not found: " + id));
    }
}
