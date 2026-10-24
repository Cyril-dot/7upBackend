package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Mapped under both the hyphenated path the frontend uses and the legacy
 * unhyphenated one, so existing callers keep working.
 */
@RestController
@RequestMapping({
        "/api/super-admin/commission",
        "/api/superadmin/commission"
})
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class SuperAdminCommissionController {

    private final SuperAdminCommissionService commissionService;
    private final SuperAdminCountryReportService countryReportService;
    private final SuperAdminCommissionOperationsService operationsService;
    private final com.speedbet.api.user.HierarchyService hierarchy;

    private boolean isOwner(com.speedbet.api.user.User actor) {
        return actor != null && actor.getRole() == com.speedbet.api.user.UserRole.SITE_OWNER;
    }

    private void requireAdminOf(com.speedbet.api.user.User actor, java.util.UUID adminId) {
        if (isOwner(actor)) return;
        if (!hierarchy.isAdminOf(actor.getId(), adminId)) {
            throw com.speedbet.api.common.ApiException.forbidden("Admin does not belong to your branch");
        }
    }

    /**
     * Daily commission and referred-deposit detail. If adminId is supplied,
     * the response contains only that admin; otherwise it contains all admins.
     * Date is UTC and defaults to today.
     */
    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto>>> daily(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID adminId) {
        if (adminId != null) requireAdminOf(actor, adminId);
        var rows = operationsService.daily(date, adminId);
        if (!isOwner(actor) && adminId == null) {
            var ownAdmins = hierarchy.adminIdsOfSuperAdmin(actor.getId());
            rows = rows.stream().filter(r -> ownAdmins.contains(r.adminId())).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @GetMapping("/daily/{adminId}")
    public ResponseEntity<ApiResponse<List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto>>> dailyForAdmin(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireAdminOf(actor, adminId);
        return ResponseEntity.ok(ApiResponse.ok(operationsService.daily(date, adminId)));
    }

    /**
     * Mark commission as paid, without requiring an admin request.
     * Without {@code amount}: settles the full unpaid amount for one UTC day.
     * With {@code amount}: records a partial payment, deducted from the balance.
     */
    @PostMapping("/admins/{adminId}/pay")
    public ResponseEntity<ApiResponse<SuperAdminCommissionOperationsDtos.CommissionPayoutDto>> markPaid(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) BigDecimal amount) {
        requireAdminOf(actor, adminId);
        String operationId = UUID.randomUUID().toString();
        try {
            var result = amount != null
                    ? operationsService.payPartial(adminId, date, amount)
                    : operationsService.markPaid(adminId, date);
            return ResponseEntity.ok(ApiResponse.ok(result, amount != null ? "Partial commission payment recorded" : "Commission day marked as paid"));
        } catch (RuntimeException ex) {
            log.error("commission.pay.http-failed operationId={} adminId={} date={} amount={} type={} message={}", operationId, adminId, date, amount, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Commission payout failed. operationId=" + operationId + " reason=" + safeMessage(ex)));
        }
    }

    /** Top up one admin's commission for a UTC day to deposits × current rate. Idempotent. */
    @PostMapping("/admins/{adminId}/reconcile")
    public ResponseEntity<ApiResponse<SuperAdminCommissionOperationsDtos.ReconcileCommissionDto>> reconcile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @PathVariable UUID adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireAdminOf(actor, adminId);
        String operationId = UUID.randomUUID().toString();
        try {
            return ResponseEntity.ok(ApiResponse.ok(operationsService.reconcile(adminId, date), "Commission reconciled"));
        } catch (RuntimeException ex) {
            log.error("commission.reconcile.http-failed operationId={} adminId={} date={} type={} message={}", operationId, adminId, date, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Commission reconcile failed. operationId=" + operationId + " reason=" + safeMessage(ex)));
        }
    }

    /** Pay and clear unpaid commission entries for one UTC day across all admins. */
    @PostMapping("/clear")
    public ResponseEntity<ApiResponse<SuperAdminCommissionOperationsDtos.ClearCommissionResult>> clearAll(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!isOwner(actor)) {
            throw com.speedbet.api.common.ApiException.forbidden(
                    "Clearing commission across all admins is a site-owner action; use per-admin pay for your own admins");
        }
        String operationId = UUID.randomUUID().toString();
        try {
            return ResponseEntity.ok(ApiResponse.ok(operationsService.clearAll(date), "Commission day cleared"));
        } catch (RuntimeException ex) {
            log.error("commission.clear.http-failed operationId={} date={} type={} message={}", operationId, date, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Commission clear failed. operationId=" + operationId + " reason=" + safeMessage(ex)));
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    // ─── Per-admin (legacy shape) ───────────────────────────────────────────

    @GetMapping("/by-admin/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionPeriodDto>>> dailyByAdmin(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionByAdmin(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionByAdmin(days, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/by-admin/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionPeriodDto>>> weeklyByAdmin(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionByAdmin(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionByAdmin(weeks, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    // ─── Platform totals (legacy shape) ─────────────────────────────────────

    @GetMapping("/totals/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> dailyTotals(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionTotals(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionTotals(days, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/totals/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> weeklyTotals(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionTotals(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionTotals(weeks, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    // ─── Split by the referred user's country ───────────────────────────────

    @GetMapping("/by-country/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.CountryPeriodTotalDto>>> dailyByCountry(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByCountry(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByCountry(days, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/by-country/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.CountryPeriodTotalDto>>> weeklyByCountry(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByCountry(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByCountry(weeks, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/by-admin-country/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionCountryDto>>> dailyByAdminCountry(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByAdminAndCountry(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByAdminAndCountry(days, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/by-admin-country/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionCountryDto>>> weeklyByAdminCountry(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByAdminAndCountry(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByAdminAndCountry(weeks, hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    // ─── Combined report — one call, everything the analytics page needs ─────

    @GetMapping("/country-report/daily")
    public ResponseEntity<ApiResponse<SuperAdminDtos.CountrySplitReportDto>> dailyReport(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildDaily(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildDaily(days, hierarchy.subtreeWalletIds(actor.getId()), hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }

    @GetMapping("/country-report/weekly")
    public ResponseEntity<ApiResponse<SuperAdminDtos.CountrySplitReportDto>> weeklyReport(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (isOwner(actor)) {
            return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildWeekly(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildWeekly(weeks, hierarchy.subtreeWalletIds(actor.getId()), hierarchy.adminIdsOfSuperAdmin(actor.getId()))));
    }
}
