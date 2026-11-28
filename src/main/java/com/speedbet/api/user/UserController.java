package com.speedbet.api.user;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final WalletService walletService;
    private final ReferralService referralService;
    private final UserRepository userRepo;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal User user) {
        var wallet = walletService.getWallet(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "user", AuthDtos.UserDto.from(user),
                "wallet", Map.of(
                        "balance", wallet.getBalance(),
                        "currency", wallet.getCurrency()
                )
        )));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<AuthDtos.UserDto>> update(
            @AuthenticationPrincipal User user,
            @RequestBody AuthDtos.UpdateProfileRequest req) {
        var updated = userService.updateProfile(
                user.getId(), req.firstName(), req.lastName(),
                req.phone(), req.country(), req.themePreference()
        );
        return ResponseEntity.ok(ApiResponse.ok(AuthDtos.UserDto.from(updated)));
    }

    /** v217: one-time referral-code set (Account page). */
    @PostMapping("/me/referral")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setReferral(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body) {
        String code = body.get("referralCode");
        if (code == null) code = body.get("code");
        var link = referralService.setReferralForUser(user.getId(), code, user.getEmail());
        var admin = userRepo.findById(link.getAdminId()).orElse(null);
        String adminName = "";
        if (admin != null) {
            adminName = ((admin.getFirstName() == null ? "" : admin.getFirstName()) + " "
                    + (admin.getLastName() == null ? "" : admin.getLastName())).trim();
            if (adminName.isEmpty()) adminName = admin.getEmail() == null ? "" : admin.getEmail();
        }
        var fresh = userRepo.findById(user.getId()).orElse(user);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "hasReferral", true,
                "referralCode", link.getCode(),
                "adminName", adminName,
                "adminEmail", admin == null || admin.getEmail() == null ? "" : admin.getEmail(),
                "user", AuthDtos.UserDto.from(fresh)
        )));
    }

    /** v217: the referral a user is currently under, if any. */
    @GetMapping("/me/referral")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myReferral(
            @AuthenticationPrincipal User user) {
        var linkOpt = referralService.findReferralLinkForUser(user.getId());
        if (linkOpt.isEmpty())
            return ResponseEntity.ok(ApiResponse.ok(Map.of("hasReferral", false)));
        var link = linkOpt.get();
        var admin = userRepo.findById(link.getAdminId()).orElse(null);
        String adminName = "";
        if (admin != null) {
            adminName = ((admin.getFirstName() == null ? "" : admin.getFirstName()) + " "
                    + (admin.getLastName() == null ? "" : admin.getLastName())).trim();
            if (adminName.isEmpty()) adminName = admin.getEmail() == null ? "" : admin.getEmail();
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "hasReferral", true,
                "referralCode", link.getCode(),
                "adminName", adminName,
                "adminEmail", admin == null || admin.getEmail() == null ? "" : admin.getEmail()
        )));
    }
}