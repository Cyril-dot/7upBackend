package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 7UP ID-verification review queue, SUPER_ADMIN only.
 * GET  /api/admin/kyc/pending            — pending submissions, newest first
 * POST /api/admin/kyc/{id}/approve       — approve (no body needed)
 * POST /api/admin/kyc/{id}/reject        — reject, body { reason } or { note } (required)
 *
 * SECURITY — READ THIS BEFORE REMOVING THE RULE BELOW: the web rule
 * `.requestMatchers("/api/admin/**").hasRole("ADMIN")` in SecurityConfig
 * is strict-role (the RoleHierarchy bean is wired only to method
 * security, NOT to web requestMatchers), so without an earlier, more
 * specific rule a SUPER_ADMIN would get 403 on /api/admin/kyc/** before
 * ever reaching this controller. SecurityConfig MUST contain, placed
 * BEFORE the /api/admin/** line (Spring Security matches in order):
 *     .requestMatchers("/api/admin/kyc/**").hasRole("SUPER_ADMIN")
 * The code-level check below is defense in depth for the same rule.
 */
@RestController
@RequestMapping("/api/admin/kyc")
@RequiredArgsConstructor
public class AdminKycController {

    private final KycService kycService;

    private void requireSuperAdmin(User admin) {
        if (admin == null || admin.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Super admin only.");
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<KycDtos.KycSubmissionResponse>>> pending(
            @AuthenticationPrincipal User admin) {
        requireSuperAdmin(admin);
        List<KycDtos.KycSubmissionResponse> rows = kycService.list("PENDING").stream()
                .map(KycDtos.KycSubmissionResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<KycDtos.KycSubmissionResponse>> approve(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id) {
        requireSuperAdmin(admin);
        KycDtos.ReviewKycRequest req = new KycDtos.ReviewKycRequest();
        req.setDecision("APPROVED");
        KycSubmission saved = kycService.review(admin, id, req);
        return ResponseEntity.ok(ApiResponse.ok(
                KycDtos.KycSubmissionResponse.from(saved), "KYC APPROVED"));
    }

    @Data
    public static class RejectKycRequest {
        private String reason;
        private String note;
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<KycDtos.KycSubmissionResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @RequestBody(required = false) RejectKycRequest body) {
        requireSuperAdmin(admin);
        String reason = null;
        if (body != null) {
            if (body.getReason() != null && !body.getReason().isBlank()) {
                reason = body.getReason();
            } else if (body.getNote() != null && !body.getNote().isBlank()) {
                reason = body.getNote();
            }
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A rejection reason is required.");
        }
        KycDtos.ReviewKycRequest req = new KycDtos.ReviewKycRequest();
        req.setDecision("REJECTED");
        req.setNote(reason.trim());
        KycSubmission saved = kycService.review(admin, id, req);
        return ResponseEntity.ok(ApiResponse.ok(
                KycDtos.KycSubmissionResponse.from(saved), "KYC REJECTED"));
    }
}
