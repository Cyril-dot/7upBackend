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

/**
 * Super-admin endpoints for requesting payout of their own commission
 * from the site owner.
 *
 * Lifecycle: REQUESTED → APPROVED → PAID (owner side) / REJECTED.
 * Base path: /api/super-admin/commission/payout-requests
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/commission/payout-requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminPayoutController {

    private final SuperAdminPayoutService payoutService;

    /** POST — request payout of the full current commission balance. */
    @PostMapping
    public ResponseEntity<ApiResponse<SuperAdminPayoutRequest>> request(
            @AuthenticationPrincipal User actor) {
        log.info("request: saId={}", actor.getId());
        return ResponseEntity.ok(ApiResponse.ok(payoutService.requestPayout(actor.getId())));
    }

    /** GET — own payout requests, newest first. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<SuperAdminPayoutRequest>>> list(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.listOwn(actor.getId())));
    }

    /** GET /balance — current owed balance, lifetime figures, rate, open-request flag. Read-only. */
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> balance(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(
                payoutService.balanceOf(actor.getId(), actor.getSuperAdminCommissionPercent())));
    }
}
