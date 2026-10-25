package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Site-owner endpoints for paying super-admin commission.
 *
 * Base path: /api/site-owner/commission
 *
 * Read-only by default; every write (approve / reject / mark-paid /
 * pay-all / auto-settle toggle) is audit-logged in the service.
 */
@Slf4j
@RestController
@RequestMapping("/api/site-owner/commission")
@PreAuthorize("hasRole('SITE_OWNER')")
@RequiredArgsConstructor
public class SiteOwnerPayoutController {

    private final SuperAdminPayoutService payoutService;

    /** GET /payout-requests?status=ALL|REQUESTED|APPROVED|PAID|REJECTED — newest first, with SA email. */
    @GetMapping("/payout-requests")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.listForOwner(status)));
    }

    /** POST /payout-requests/{id}/approve — REQUESTED → APPROVED. Moves no money. */
    @PostMapping("/payout-requests/{id}/approve")
    public ResponseEntity<ApiResponse<SuperAdminPayoutRequest>> approve(
            @AuthenticationPrincipal User actor, @PathVariable UUID id) {
        log.info("approve: payoutId={} ownerId={}", id, actor.getId());
        return ResponseEntity.ok(ApiResponse.ok(payoutService.approve(id, actor.getId())));
    }

    /** POST /payout-requests/{id}/reject {reason} — REQUESTED → REJECTED. Balance untouched. */
    @PostMapping("/payout-requests/{id}/reject")
    public ResponseEntity<ApiResponse<SuperAdminPayoutRequest>> reject(
            @AuthenticationPrincipal User actor, @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        log.info("reject: payoutId={} ownerId={}", id, actor.getId());
        return ResponseEntity.ok(ApiResponse.ok(payoutService.reject(id, actor.getId(), body.get("reason"))));
    }

    /**
     * POST /payout-requests/{id}/mark-paid — APPROVED → PAID and debits the
     * snapshotted amount from the SA's balance. This is the owner's
     * confirmation that the off-platform transfer was sent.
     */
    @PostMapping("/payout-requests/{id}/mark-paid")
    public ResponseEntity<ApiResponse<SuperAdminPayoutRequest>> markPaid(
            @AuthenticationPrincipal User actor, @PathVariable UUID id) {
        log.info("markPaid: payoutId={} ownerId={}", id, actor.getId());
        return ResponseEntity.ok(ApiResponse.ok(payoutService.markPaid(id, actor.getId())));
    }

    /** POST /payout-requests/pay-all — pay + clear every SA balance in one run (skips open requests). */
    @PostMapping("/payout-requests/pay-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payAll(
            @AuthenticationPrincipal User actor) {
        log.info("payAll: ownerId={}", actor.getId());
        return ResponseEntity.ok(ApiResponse.ok(payoutService.payAll(actor.getId(), "MANUAL")));
    }

    /**
     * GET /super-admins/{id}/payout-detail?days=30 — per-day commission
     * (ledger, UTC days, only days with commission), lifetime figures,
     * and the SA's full payout-request history. Feeds the Payouts tab
     * inside the SA detail card. Read-only.
     */
    @GetMapping("/super-admins/{id}/payout-detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payoutDetail(
            @PathVariable UUID id, @RequestParam(required = false, defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.payoutDetail(id, days)));
    }

    /** GET /payout-summary — total owed, pending/approved counts, lifetime paid, auto-settle flag. */
    @GetMapping("/payout-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.summary()));
    }

    /** GET /payout-settings — the auto-settle toggle (row created on first read). */
    @GetMapping("/payout-settings")
    public ResponseEntity<ApiResponse<SuperAdminPayoutSettings>> settings() {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.getSettings()));
    }

    /** PUT /payout-settings {autoSettleDaily} — owner-only toggle. */
    @PutMapping("/payout-settings")
    public ResponseEntity<ApiResponse<SuperAdminPayoutSettings>> setSettings(
            @AuthenticationPrincipal User actor, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("autoSettleDaily"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("autoSettleDaily")));
        log.info("setSettings: ownerId={} autoSettleDaily={}", actor.getId(), enabled);
        return ResponseEntity.ok(ApiResponse.ok(payoutService.setAutoSettle(enabled, actor.getId())));
    }
}
