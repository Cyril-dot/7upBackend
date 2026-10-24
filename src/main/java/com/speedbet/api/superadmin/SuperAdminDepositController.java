package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({
        "/api/super-admin/deposits",
        "/api/superadmin/deposits"
})
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminDepositController {

    private final SuperAdminDepositService depositService;
    private final com.speedbet.api.user.HierarchyService hierarchy;

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> daily(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "30") int days) {
        if (actor != null && actor.getRole() == com.speedbet.api.user.UserRole.SITE_OWNER) {
            return ResponseEntity.ok(ApiResponse.ok(depositService.getDaily(days)));
        }
        return ResponseEntity.ok(ApiResponse.ok(depositService.getDaily(days,
                hierarchy.subtreeWalletIds(actor.getId()))));
    }

    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> weekly(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.speedbet.api.user.User actor,
            @RequestParam(defaultValue = "12") int weeks) {
        if (actor != null && actor.getRole() == com.speedbet.api.user.UserRole.SITE_OWNER) {
            return ResponseEntity.ok(ApiResponse.ok(depositService.getWeekly(weeks)));
        }
        return ResponseEntity.ok(ApiResponse.ok(depositService.getWeekly(weeks,
                hierarchy.subtreeWalletIds(actor.getId()))));
    }
}