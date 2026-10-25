package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SITE OWNER command-centre stats — read-only endpoints.
 *
 * Only SITE_OWNER (URL guard in SecurityConfig + class @PreAuthorize).
 * These never return User entities, only maps, so no credential field
 * can leak. All money figures come from OwnerStatsService, where amounts
 * are grouped by wallet currency and never summed across GH₵/₦.
 *
 *   GET /api/site-owner/stats/overview      — counts, money today /
 *       yesterday / last 7 / last 30 / all-time (per currency), 30-day
 *       daily series, commission owed/paid, biggest branches, newest staff
 *   GET /api/site-owner/stats/super-admins  — every super admin, full data:
 *       contact, status, commission rate + balance + lifetime earned,
 *       admin count, user count, deposits today / 30d / all-time,
 *       withdrawals, deposit count, and their admins
 *   GET /api/site-owner/stats/admins        — every admin, full data:
 *       contact, status, WHICH super admin they belong to (or owner-direct
 *       when they belong to none), link commission rate, commission
 *       balance + lifetime earned, referred-user count, deposits
 *       today / 30d / all-time, withdrawals, deposit count
 */
@Slf4j
@RestController
@RequestMapping("/api/site-owner/stats")
@PreAuthorize("hasRole('SITE_OWNER')")
@RequiredArgsConstructor
public class OwnerStatsController {

    private final OwnerStatsService stats;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(stats.overview()));
    }

    @GetMapping("/super-admins")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> superAdmins() {
        return ResponseEntity.ok(ApiResponse.ok(stats.superAdmins()));
    }

    @GetMapping("/admins")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> admins() {
        return ResponseEntity.ok(ApiResponse.ok(stats.admins()));
    }
}
