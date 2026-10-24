package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Super-admin endpoints for managing admin affiliate payout requests.
 *
 * Lifecycle:  REQUESTED → APPROVED → PAID
 *                       ↘ REJECTED
 *
 * Base path: /api/super-admin/payout-requests
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/payout-requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PayoutSuperAdminController {

    private final AdminAffiliateService adminAffiliateService;
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

    /**
     * GET /api/super-admin/payout-requests
     * List all payout requests in REQUESTED status awaiting review.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PayoutRequest>>> getPending(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor) {
        var requests = adminAffiliateService.getPayoutRequestsByStatus(PayoutStatus.REQUESTED);
        if (!isOwner(actor)) {
            var ownAdmins = hierarchy.adminIdsOfSuperAdmin(actor.getId());
            requests = requests.stream().filter(r -> ownAdmins.contains(r.getAdminId())).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(requests));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/approve
     * Approve a REQUESTED payout. Wallet is not debited yet.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<PayoutRequest>> approve(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID id) {
        log.info("approve: payoutId={}", id);
        requireAdminOf(actor, adminAffiliateService.getPayoutRequest(id).getAdminId());
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.approve(id)));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/reject
     * Reject a REQUESTED payout with an optional reason.
     * Wallet is not touched (no debit was made at request time).
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<PayoutRequest>> reject(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        log.info("reject: payoutId={}", id);
        requireAdminOf(actor, adminAffiliateService.getPayoutRequest(id).getAdminId());
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.reject(id, body.get("reason"))));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/mark-paid
     * Mark an APPROVED payout as PAID.
     * This triggers WalletService.debit — the snapshotted amount is removed
     * from the admin's affiliate wallet balance.
     */
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<ApiResponse<PayoutRequest>> markPaid(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID id) {
        log.info("markPaid: payoutId={}", id);
        requireAdminOf(actor, adminAffiliateService.getPayoutRequest(id).getAdminId());
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.markPaid(id)));
    }
}