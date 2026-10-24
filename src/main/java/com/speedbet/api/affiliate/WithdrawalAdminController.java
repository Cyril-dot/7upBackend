package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Super-admin endpoints for processing affiliate withdrawal requests.
 * Only SUPER_ADMIN role can access these.
 *
 * IMPORTANT — balance separation:
 *   These withdrawals represent COMMISSION earnings, not the user's main wallet.
 *   Rejecting a withdrawal reverses the amount back to the commission balance
 *   (via AffiliateCommissionService), NOT the main wallet (WalletService).
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/affiliate-withdrawals")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class WithdrawalAdminController {

    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final AffiliateCommissionService commissionService;   // ← commission balance, NOT wallet
    private final com.speedbet.api.user.HierarchyService hierarchy;

    private boolean isOwner(com.speedbet.api.user.User actor) {
        return actor != null && actor.getRole() == com.speedbet.api.user.UserRole.SITE_OWNER;
    }

    private void requireAdminOf(com.speedbet.api.user.User actor, UUID adminId) {
        if (isOwner(actor)) return;
        if (!hierarchy.isAdminOf(actor.getId(), adminId)) {
            throw com.speedbet.api.common.ApiException.forbidden("Admin does not belong to your branch");
        }
    }

    /** List all PENDING affiliate withdrawal requests. */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<AffiliateWithdrawalRequest>>> getPending(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor) {
        var pending = withdrawalRepo.findByStatus(AffiliateWithdrawalStatus.PENDING);
        if (!isOwner(actor)) {
            var ownAdmins = hierarchy.adminIdsOfSuperAdmin(actor.getId());
            pending = pending.stream().filter(w -> ownAdmins.contains(w.getUserId())).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(pending));
    }

    /**
     * Mark an affiliate withdrawal as processed (bank/MoMo transfer completed by ops team).
     * Commission balance was already zeroed at payout-request creation time.
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> process(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID id) {
        log.info("process: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);
        requireAdminOf(actor, withdrawal.getUserId());

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        withdrawal.setStatus(AffiliateWithdrawalStatus.PROCESSED);
        withdrawal.setProcessedAt(Instant.now());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    /**
     * Reject a withdrawal and return the commission amount back to the affiliate's
     * COMMISSION balance (not their main wallet — these are two separate ledgers).
     *
     * The commission funds will then be swept again in the next daily payout run.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> reject(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {

        log.info("reject: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);
        requireAdminOf(actor, withdrawal.getUserId());

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        // ── Reverse into COMMISSION balance, not main wallet ──────────────────
        // The original debit was against the affiliate's commission ledger.
        // Reversing to the main wallet would incorrectly inflate the user's
        // spendable balance with funds they earned as referral commission.
        commissionService.reverseCommissionPayout(
                withdrawal.getUserId(),
                withdrawal.getAmount());

        withdrawal.setStatus(AffiliateWithdrawalStatus.REJECTED);
        withdrawal.setRejectReason(body.getOrDefault("reason", "No reason provided"));
        withdrawal.setProcessedAt(Instant.now());

        log.info("reject: commission balance re-credited userId={} amount={} (NOT main wallet)",
                withdrawal.getUserId(), withdrawal.getAmount());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    private AffiliateWithdrawalRequest getById(UUID id) {
        return withdrawalRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Affiliate withdrawal request not found"));
    }
}