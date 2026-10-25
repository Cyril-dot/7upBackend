package com.speedbet.api.gateway;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Site owner side of the gateway workflow: the owner SETS.
 * Approving a request, rejecting it, or setting a branch directly
 * are the only writes that ever change a branch's active gateways.
 */
@RestController
@RequestMapping("/api/site-owner/gateway")
@PreAuthorize("hasRole('SITE_OWNER')")
@RequiredArgsConstructor
public class SiteOwnerGatewayController {

    private final GatewayService gatewayService;
    private final UserRepository userRepo;
    private final BranchGatewaySettingRepository settingRepo;
    private final GatewayRequestRepository requestRepo;

    @GetMapping("/requests")
    public ResponseEntity<ApiResponse<Object>> requests(
            @RequestParam(defaultValue = "PENDING") String status) {
        var list = requestRepo.findByStatusOrderByRequestedAtAsc(status.trim().toUpperCase())
                .stream().map(gatewayService::requestRow).toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping("/requests/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @AuthenticationPrincipal User owner,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.approveRequest(owner, id),
                "Gateway request approved — the branch's gateways are now live."));
    }

    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @AuthenticationPrincipal User owner,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.rejectRequest(owner, id),
                "Gateway request rejected — the branch keeps its current gateways."));
    }

    @GetMapping("/branches")
    public ResponseEntity<ApiResponse<Object>> branches() {
        var rows = userRepo.findAllByRole(UserRole.SUPER_ADMIN).stream().map(sa -> {
            var setting = settingRepo.findBySuperAdminId(sa.getId()).orElse(null);
            var row = new LinkedHashMap<String, Object>();
            row.put("superAdminId", sa.getId().toString());
            row.put("superAdminEmail", sa.getEmail());
            row.put("status", sa.getStatus() == null ? null : sa.getStatus().name());
            row.put("ghGateway", setting == null ? GatewayService.DEFAULT_GH : setting.getGhGateway());
            row.put("ngGateway", setting == null ? GatewayService.DEFAULT_NG : setting.getNgGateway());
            row.put("isBranchDefault", setting == null);
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @PutMapping("/branches/{saId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setBranch(
            @AuthenticationPrincipal User owner,
            @PathVariable UUID saId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(ApiResponse.ok(
                gatewayService.setDirect(owner, saId, req.get("ghGateway"), req.get("ngGateway")),
                "Branch gateways set. Any pending request for this branch was superseded."));
    }
}
