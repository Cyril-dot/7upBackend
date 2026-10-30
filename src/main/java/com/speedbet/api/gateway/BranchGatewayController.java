package com.speedbet.api.gateway;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deposit-page lookup: which gateways does THIS user's branch
 * actually use right now? Returns the branch's ACTIVE gateways
 * (owner-set), or the platform defaults when the user has no
 * branch or the branch has no setting yet. Pending requests are
 * never returned here — they are not live.
 */
@RestController
@RequestMapping("/api/wallet/deposit")
@RequiredArgsConstructor
public class BranchGatewayController {

    private final GatewayService gatewayService;

    @GetMapping("/gateway")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myBranchGateway(
            @AuthenticationPrincipal User user) {
        var out = new LinkedHashMap<String, Object>(gatewayService.activeFor(user));
        out.put("limits", gatewayService.branchLimitsFor(user));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }
}
