package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for Ghana manual MoMo deposit submissions and admin review
 * (HolyStar-style flow, v179).
 *
 * The user pays externally via *170# MoMoPay &amp; Pay Bill to the branch's
 * MoMoPay merchant ID, then submits the 11-digit Transaction ID plus a
 * receipt screenshot. The deposit sits PENDING until a super admin approves
 * it (wallet credited) or rejects it.
 *
 * NOTE on paths: /api/admin/momo-deposits already belongs to BankDeposit
 * rows (small-amount manual proofs), so this queue lives under
 * /api/admin/manual-momo-deposits to avoid an ambiguous-mapping clash.
 *
 * User-facing:
 *   GET   /api/wallet/momo-deposits           — user's own history
 *   POST  /api/wallet/momo-deposits           — submit a manual MoMo deposit
 *
 * Admin-facing (ROLE_SUPER_ADMIN required):
 *   GET   /api/admin/manual-momo-deposits            — all deposits
 *   GET   /api/admin/manual-momo-deposits/pending    — pending queue
 *   GET   /api/admin/manual-momo-deposits/{id}       — single deposit detail
 *   POST  /api/admin/manual-momo-deposits/{id}/approve
 *   POST  /api/admin/manual-momo-deposits/{id}/reject
 */
@RestController
@RequiredArgsConstructor
public class MomoDepositController {

    private final MomoDepositService service;

    @GetMapping("/api/wallet/momo-deposits")
    public ResponseEntity<ApiResponse<PageResponse<MomoDepositDtos.DepositResponse>>> myDeposits(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getMyDeposits(user.getId(), PageRequest.of(page, size))
                            .map(MomoDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    /**
     * User submits a manual MoMo deposit (Transaction ID + screenshot URL).
     * Goes to PENDING for super-admin review.
     */
    @PostMapping("/api/wallet/momo-deposits")
    public ResponseEntity<ApiResponse<MomoDepositDtos.DepositResponse>> submitDeposit(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody MomoDepositDtos.SubmitRequest req) {

        var deposit = service.submit(user.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                MomoDepositDtos.DepositResponse.from(deposit),
                "Deposit submitted. It will be reviewed shortly."
        ));
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping("/api/admin/manual-momo-deposits")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<MomoDepositDtos.DepositResponse>>> allDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getAll(PageRequest.of(page, size))
                            .map(d -> MomoDepositDtos.DepositResponse.from(d, service.getUserEmail(d.getUserId()), false));
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/manual-momo-deposits/pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<MomoDepositDtos.DepositResponse>>> pendingDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getPending(PageRequest.of(page, size))
                            .map(d -> MomoDepositDtos.DepositResponse.from(d, service.getUserEmail(d.getUserId()), false));
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/manual-momo-deposits/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MomoDepositDtos.DepositResponse>> getDeposit(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(
                MomoDepositDtos.DepositResponse.from(service.getById(id))
        ));
    }

    @PostMapping("/api/admin/manual-momo-deposits/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MomoDepositDtos.DepositResponse>> approve(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody MomoDepositDtos.ApproveRequest req) {

        var deposit = service.approve(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                MomoDepositDtos.DepositResponse.from(deposit),
                "Deposit approved. GH₵" + req.getCreditedGhsAmount() + " credited to user wallet."
        ));
    }

    @PostMapping("/api/admin/manual-momo-deposits/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MomoDepositDtos.DepositResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody MomoDepositDtos.RejectRequest req) {

        var deposit = service.reject(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                MomoDepositDtos.DepositResponse.from(deposit),
                "Deposit rejected."
        ));
    }
}
