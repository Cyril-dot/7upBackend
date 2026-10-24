package com.speedbet.api.superadmin;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.HierarchyService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.user.UserService;
import com.speedbet.api.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SITE OWNER endpoints — the top of the hierarchy.
 *
 * Only SITE_OWNER can reach these routes (URL guard in SecurityConfig +
 * the class-level @PreAuthorize below). The owner:
 *   - creates super admins and SETS their commission rate,
 *   - adjusts a super admin's rate, deactivates / reactivates super admins,
 *   - sees the global dashboard and every super admin's branch.
 *
 * Super admins NEVER reach this controller: they manage only their own
 * admins through /api/super-admin/**, and every one of those endpoints is
 * scoped to their subtree (see HierarchyService).
 *
 * NOTE: responses are maps, never User entities, so no credential field
 * can ever leak through this controller.
 */
@Slf4j
@RestController
@RequestMapping("/api/site-owner")
@PreAuthorize("hasRole('SITE_OWNER')")
@RequiredArgsConstructor
public class SiteOwnerController {

    private final UserRepository userRepo;
    private final UserService userService;
    private final HierarchyService hierarchy;
    private final SuperAdminQueryService queryService;
    private final AuditService auditService;

    // ─── Create a super admin (owner sets the commission rate) ──────────

    /**
     * POST /api/site-owner/super-admins
     * Body: email, password, firstName, lastName, commissionRate (0-100).
     * The rate is the super admin's cut of every confirmed deposit from
     * users inside their subtree (see ReferralService.attributeCommission).
     */
    @PostMapping("/super-admins")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSuperAdmin(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        var rate = new BigDecimal(req.get("commissionRate"));
        var sa = userService.createSuperAdmin(
                req.get("email"), req.get("password"),
                req.get("firstName"), req.get("lastName"),
                actor.getId(), rate);
        auditService.log(actor.getId(), "CREATE_SUPER_ADMIN", "users", sa.getId(),
                null, Map.of("email", sa.getEmail(), "commissionRate", rate.toPlainString()), null);
        return ResponseEntity.ok(ApiResponse.ok(toRow(sa),
                "Super admin created with " + rate + "% commission rate"));
    }

    // ─── Adjust a super admin's commission rate ─────────────────────────

    @PatchMapping("/super-admins/{id}/commission-rate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateCommissionRate(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID id,
            @RequestBody Map<String, String> req) {
        var rate = new BigDecimal(req.get("commissionRate"));
        var sa = userService.updateSuperAdminCommissionRate(id, rate);
        auditService.log(actor.getId(), "UPDATE_SUPER_ADMIN_COMMISSION_RATE", "users", id,
                null, Map.of("commissionRate", rate.toPlainString()), null);
        return ResponseEntity.ok(ApiResponse.ok(toRow(sa),
                "Commission rate updated to " + rate + "%"));
    }

    // ─── Deactivate / reactivate a super admin ──────────────────────────
    // A DISABLED super admin stops earning their cut (see ReferralService)
    // but their admins keep earning theirs — deactivation never punishes
    // the branch below.

    @PatchMapping("/super-admins/{id}/deactivate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID id) {
        var sa = requireSuperAdmin(id);
        sa.setStatus(UserStatus.DISABLED);
        userRepo.save(sa);
        auditService.log(actor.getId(), "DEACTIVATE_SUPER_ADMIN", "users", id, null, null, null);
        return ResponseEntity.ok(ApiResponse.ok(toRow(sa), "Super admin deactivated"));
    }

    @PatchMapping("/super-admins/{id}/activate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID id) {
        var sa = requireSuperAdmin(id);
        sa.setStatus(UserStatus.ACTIVE);
        userRepo.save(sa);
        auditService.log(actor.getId(), "ACTIVATE_SUPER_ADMIN", "users", id, null, null, null);
        return ResponseEntity.ok(ApiResponse.ok(toRow(sa), "Super admin activated"));
    }

    // ─── List + detail ──────────────────────────────────────────────────

    @GetMapping("/super-admins")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSuperAdmins() {
        var rows = userRepo.findAllByRole(UserRole.SUPER_ADMIN).stream()
                .map(this::toRow)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @GetMapping("/super-admins/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSuperAdmin(@PathVariable UUID id) {
        var sa = requireSuperAdmin(id);
        var row = toRow(sa);
        row.put("admins", hierarchy.adminsOfSuperAdmin(id).stream()
                .map(a -> Map.of(
                        "id", (Object) a.getId(),
                        "email", (Object) a.getEmail(),
                        "status", (Object) a.getStatus().name()))
                .toList());
        return ResponseEntity.ok(ApiResponse.ok(row));
    }

    // ─── Global dashboard ───────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard() {
        var sas = userRepo.findAllByRole(UserRole.SUPER_ADMIN);
        var branches = sas.stream().map(sa -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", sa.getId());
            b.put("email", sa.getEmail());
            b.put("status", sa.getStatus().name());
            b.put("commissionRate", sa.getSuperAdminCommissionPercent());
            b.put("adminCount", hierarchy.adminIdsOfSuperAdmin(sa.getId()).size());
            b.put("userCount", hierarchy.subtreeUserCount(sa.getId()));
            return b;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalUsers", userRepo.count());
        out.put("totalAdmins", userRepo.countByRole(UserRole.ADMIN));
        out.put("totalSuperAdmins", sas.size());
        out.put("revenue", queryService.getRevenueOverview());
        out.put("branches", branches);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private User requireSuperAdmin(UUID id) {
        var user = userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Super admin not found"));
        if (user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.badRequest("Target user is not a super admin");
        }
        return user;
    }

    private Map<String, Object> toRow(User sa) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", sa.getId());
        row.put("email", sa.getEmail());
        row.put("firstName", sa.getFirstName());
        row.put("lastName", sa.getLastName());
        row.put("phone", sa.getPhone());
        row.put("country", sa.getCountry());
        row.put("status", sa.getStatus().name());
        row.put("commissionRate", sa.getSuperAdminCommissionPercent());
        row.put("adminCount", hierarchy.adminIdsOfSuperAdmin(sa.getId()).size());
        row.put("userCount", hierarchy.subtreeUserCount(sa.getId()));
        return row;
    }
}
