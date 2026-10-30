package com.speedbet.api.gateway;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Super admin side of the gateway workflow: the admin sets their OWN
 * branch gateways directly (Ghana AkwaPay, Nigeria manual bank transfer)
 * via /direct below — no site-owner approval. The legacy /request
 * endpoint still creates a PENDING request for the owner to approve or
 * reject, and the owner can still override any branch directly (see
 * SiteOwnerGatewayController).
 */
@RestController
@RequestMapping("/api/super-admin/gateway")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminGatewayController {

    private final GatewayService gatewayService;

    private void requireSuperAdmin(User actor) {
        // The role hierarchy lets SITE_OWNER pass a SUPER_ADMIN guard;
        // the owner must use the owner endpoints, never request for a
        // branch as if they were its super admin.
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a super admin can manage gateways for their branch.");
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> myGateway(
            @AuthenticationPrincipal User actor) {
        requireSuperAdmin(actor);
        var active = gatewayService.activeFor(actor);
        var pending = gatewayService.requestRowOrNull(actor.getId());
        var out = new LinkedHashMap<String, Object>();
        out.put("active", active);
        out.put("pendingRequest", pending);
        out.put("allowedGh", GatewayService.ALLOWED_GH);
        out.put("allowedNg", GatewayService.ALLOWED_NG);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestGateway(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        requireSuperAdmin(actor);
        var saved = gatewayService.requestGateway(actor, req.get("ghGateway"), req.get("ngGateway"));
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.requestRow(saved),
                "Gateway request sent to the site owner. Your current gateways stay live until the owner sets them."));
    }

    /**
     * Direct set of the branch's OWN gateways — Ghana AkwaPay and/or
     * Nigeria manual bank transfer. Goes live immediately for every user
     * on this super admin's branch. Plain ADMINs and the site owner are
     * refused by requireSuperAdmin.
     */
    @PutMapping("/direct")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setOwnGateways(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        requireSuperAdmin(actor);
        var saved = gatewayService.setOwnGateways(actor, req.get("ghGateway"), req.get("ngGateway"));
        return ResponseEntity.ok(ApiResponse.ok(saved,
                "Branch gateways saved. They are live now for users on your branch."));
    }

    /**
     * Direct set of the branch's Nigeria MANUAL bank details (bank name,
     * account number, account name). Unlike the gateway request above, this
     * is NOT owner-approved: the details are the super admin's own receiving
     * account, and they go live on the Nigerian deposit page immediately.
     * Plain ADMINs and the site owner are refused by requireSuperAdmin.
     */
    @PutMapping("/ng-manual-bank")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setNgManualBank(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        requireSuperAdmin(actor);
        var saved = gatewayService.setNgManualBank(actor,
                req.get("bankName"), req.get("accountNumber"), req.get("accountName"));
        var out = new LinkedHashMap<String, Object>();
        out.put("ngManualBank", saved);
        return ResponseEntity.ok(ApiResponse.ok(out,
                "Nigeria manual bank details saved. Nigerian users on your branch now see them as the manual bank transfer option."));
    }
}
