package com.speedbet.api.gateway;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.user.HierarchyService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Super-admin gateway workflow, with the owner's measures in place:
 *
 *  1. A super admin DECIDES (requests) — their request never touches
 *     deposits. The branch keeps its active gateways until the owner acts.
 *  2. The site owner SETS — by approving a request, or by setting a
 *     branch directly. Only that write changes BranchGatewaySetting.
 *  3. One pending request per super admin, so no stale approve can
 *     overwrite a newer owner decision. A direct owner set supersedes
 *     (rejects) any pending request for that branch.
 *  4. Every owner write is audit-logged with before/after gateways.
 *  5. Users with no branch super admin, and branches with no setting
 *     yet, fall back to the platform defaults (GH AKWAPAY, NG GMP) —
 *     a missing setting can never break deposits.
 *
 * Allowed gateways are exactly the rails the frontend deposit page
 * can actually route today. Flutterwave GH/NG are deliberately NOT
 * allowed yet: Flutterwave NG needs a bank code per deposit and
 * Flutterwave GH needs a callback page the app does not have, so
 * offering them would be a dead choice. They can be added to these
 * two sets only together with their frontend flow.
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

    public static final String DEFAULT_GH = "AKWAPAY";
    public static final String DEFAULT_NG = "GMP";

    public static final Set<String> ALLOWED_GH = Set.of("AKWAPAY", "GMP");
    public static final Set<String> ALLOWED_NG = Set.of("GMP", "KORAPAY", "MANUAL");

    private final BranchGatewaySettingRepository settingRepo;
    private final GatewayRequestRepository requestRepo;
    private final UserRepository userRepo;
    private final ReferralLinkRepository linkRepo;
    private final HierarchyService hierarchy;
    private final AuditService auditService;

    // Server env secret that encrypts super-admin gateway credentials.
    // Direct env reference (no application.properties entry needed):
    // set GATEWAY_KEY_SECRET on Railway. Blank = fail-closed: saving
    // branch keys is refused, and reading them falls back to the
    // platform key, so a missing env var can never leak or break.
    @Value("${GATEWAY_KEY_SECRET:}")
    private String gatewayKeySecret;

    // ─── Validation ────────────────────────────────────────────────────

    private String requireGh(String gh) {
        if (gh == null || gh.isBlank()) throw ApiException.badRequest("A Ghana gateway is required.");
        var v = gh.trim().toUpperCase();
        if (!ALLOWED_GH.contains(v)) throw ApiException.badRequest("Ghana gateway must be one of: AKWAPAY, GMP.");
        return v;
    }

    private String requireNg(String ng) {
        if (ng == null || ng.isBlank()) throw ApiException.badRequest("A Nigeria gateway is required.");
        var v = ng.trim().toUpperCase();
        if (!ALLOWED_NG.contains(v)) throw ApiException.badRequest("Nigeria gateway must be one of: GMP, KORAPAY.");
        return v;
    }

    private String optGh(String gh) {
        if (gh == null || gh.isBlank()) return null;
        return requireGh(gh);
    }

    private String optNg(String ng) {
        if (ng == null || ng.isBlank()) return null;
        return requireNg(ng);
    }

    // ─── Branch resolution ─────────────────────────────────────────────

    /**
     * The super admin owning this user's branch, if any. A user belongs
     * to a branch through the admin who referred them (referral link
     * owner) or, for directly created users, through createdByAdminId.
     * Owner-direct users and admins belong to no branch: empty.
     */
    public Optional<UUID> resolveBranchSuperAdmin(User user) {
        if (user == null) return Optional.empty();
        if (user.getRole() == UserRole.SUPER_ADMIN) return Optional.of(user.getId());
        if (user.getRole() == UserRole.ADMIN) return hierarchy.superAdminOfAdmin(user.getId());
        // End user: referral link owner first, then direct creator.
        if (user.getReferredViaLinkId() != null) {
            var link = linkRepo.findById(user.getReferredViaLinkId()).orElse(null);
            if (link != null) {
                var sa = hierarchy.superAdminOfAdmin(link.getAdminId());
                if (sa.isPresent()) return sa;
            }
        }
        if (user.getCreatedByAdminId() != null) {
            var creator = userRepo.findById(user.getCreatedByAdminId()).orElse(null);
            if (creator != null) {
                if (creator.getRole() == UserRole.SUPER_ADMIN) return Optional.of(creator.getId());
                if (creator.getRole() == UserRole.ADMIN) return hierarchy.superAdminOfAdmin(creator.getId());
            }
        }
        return Optional.empty();
    }

    /**
     * Nigeria manual bank details for a branch setting, or null when the
     * super admin has not set all three. The deposit page hides the manual
     * option on null — a half-set bank detail is never shown to users.
     */
    public static Map<String, Object> ngManualBankRow(BranchGatewaySetting s) {
        if (s == null
                || s.getNgManualBankName() == null || s.getNgManualBankName().isBlank()
                || s.getNgManualAccountNumber() == null || s.getNgManualAccountNumber().isBlank()
                || s.getNgManualAccountName() == null || s.getNgManualAccountName().isBlank()) {
            return null;
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("bankName", s.getNgManualBankName());
        row.put("accountNumber", s.getNgManualAccountNumber());
        row.put("accountName", s.getNgManualAccountName());
        return row;
    }

    /** Active gateways for a user: their branch's setting, or the platform defaults. */
    public Map<String, Object> activeFor(User user) {
        var saId = resolveBranchSuperAdmin(user);
        var out = new LinkedHashMap<String, Object>();
        out.put("branchSuperAdminId", saId.map(UUID::toString).orElse(null));
        if (saId.isEmpty()) {
            out.put("ghGateway", DEFAULT_GH);
            out.put("ngGateway", DEFAULT_NG);
            out.put("ngManualBank", null);
            out.put("isBranchDefault", true);
            return out;
        }
        var setting = settingRepo.findBySuperAdminId(saId.get()).orElse(null);
        out.put("ghGateway", setting == null ? DEFAULT_GH : setting.getGhGateway());
        out.put("ngGateway", setting == null ? DEFAULT_NG : setting.getNgGateway());
        out.put("ngManualBank", ngManualBankRow(setting));
        out.put("isBranchDefault", setting == null);
        return out;
    }

    public Map<String, Object> settingRow(BranchGatewaySetting s) {
        var row = new LinkedHashMap<String, Object>();
        row.put("superAdminId", s.getSuperAdminId().toString());
        row.put("ghGateway", s.getGhGateway());
        row.put("ngGateway", s.getNgGateway());
        row.put("ngManualBank", ngManualBankRow(s));
        row.put("updatedBy", s.getUpdatedBy() == null ? null : s.getUpdatedBy().toString());
        row.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
        return row;
    }

    // ─── Super admin sets their own Nigeria manual bank details ──────────

    /**
     * Direct super-admin write — deliberately NOT the gateway-request flow.
     * These bank details are the super admin's OWN receiving account for
     * manual Nigerian deposits, so the super admin sets them directly and
     * they go live immediately. Saving details also flips the branch's
     * Nigeria gateway to MANUAL (checkout is no longer offered to Nigerian
     * users, so the stored gateway must say so). Plain ADMINs and the site
     * owner are refused here (the owner uses the owner endpoints, never a
     * branch's details).
     */
    @Transactional
    public Map<String, Object> setNgManualBank(User sa, String bankName, String accountNumber, String accountName) {
        if (sa == null || sa.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a super admin can set manual bank details for their branch.");
        }
        var bank = bankName == null ? "" : bankName.trim();
        var accNo = accountNumber == null ? "" : accountNumber.trim();
        var accName = accountName == null ? "" : accountName.trim();
        if (bank.isEmpty() || accNo.isEmpty() || accName.isEmpty()) {
            throw ApiException.badRequest("Bank name, account number and account name are all required.");
        }
        if (bank.length() > 100 || accNo.length() > 50 || accName.length() > 100) {
            throw ApiException.badRequest("Bank details are too long (bank name / account name max 100, account number max 50).");
        }
        var setting = settingRepo.findBySuperAdminId(sa.getId())
                .orElseGet(() -> BranchGatewaySetting.builder()
                        .superAdminId(sa.getId()).ghGateway(DEFAULT_GH).ngGateway(DEFAULT_NG).build());
        var before = ngManualBankRow(setting);
        var beforeNg = setting.getNgGateway();
        setting.setNgManualBankName(bank);
        setting.setNgManualAccountNumber(accNo);
        setting.setNgManualAccountName(accName);
        setting.setNgGateway("MANUAL");
        setting.setUpdatedBy(sa.getId());
        setting.setUpdatedAt(Instant.now());
        var saved = settingRepo.save(setting);
        auditService.log(sa.getId(), "SET_NG_MANUAL_BANK", "branch_gateway_settings", saved.getId(),
                before == null ? null : Map.of("ngManualBank", before, "ngGateway", beforeNg),
                Map.of("ngManualBank", ngManualBankRow(saved), "ngGateway", "MANUAL"), null);
        return ngManualBankRow(saved);

    // ─── Super admin's OWN Ghana (AkwaPay) credentials ─────────────────

    private String requireKeySecret() {
        if (gatewayKeySecret == null || gatewayKeySecret.isBlank()) {
            throw ApiException.badRequest(
                    "Gateway key encryption is not configured on the server (GATEWAY_KEY_SECRET).");
        }
        return gatewayKeySecret;
    }

    /** Masked key status only — values are never returned. */
    public Map<String, Object> ghanaKeyStatus(UUID saId) {
        var setting = settingRepo.findBySuperAdminId(saId).orElse(null);
        boolean secretSet = setting != null
                && setting.getGhAkwapaySecretKey() != null
                && !setting.getGhAkwapaySecretKey().isBlank();
        boolean whSet = setting != null
                && setting.getGhAkwapayWebhookSecret() != null
                && !setting.getGhAkwapayWebhookSecret().isBlank();
        var out = new LinkedHashMap<String, Object>();
        out.put("secretKeySet", secretSet);
        out.put("secretKeyLast4", secretSet ? setting.getGhAkwapaySecretKeyLast4() : null);
        out.put("webhookSecretSet", whSet);
        return out;
    }

    /**
     * Direct super-admin write of their OWN AkwaPay credentials for
     * Ghana deposits on their branch. Blank fields keep their current
     * value; clear=true wipes both halves back to the platform key.
     * Values are encrypted before storage; audit logs carry only
     * set/unset + last4, never a value.
     */
    @Transactional
    public Map<String, Object> setGhanaKeys(User sa, String secretKey, String webhookSecret, boolean clear) {
        if (sa == null || sa.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a super admin can set AkwaPay credentials for their branch.");
        }
        var setting = settingRepo.findBySuperAdminId(sa.getId())
                .orElseGet(() -> BranchGatewaySetting.builder()
                        .superAdminId(sa.getId()).ghGateway(DEFAULT_GH).ngGateway(DEFAULT_NG).build());
        var before = ghanaKeyStatus(sa.getId());
        if (clear) {
            setting.setGhAkwapaySecretKey(null);
            setting.setGhAkwapaySecretKeyLast4(null);
            setting.setGhAkwapayWebhookSecret(null);
        } else {
            String ks = requireKeySecret();
            boolean touched = false;
            if (secretKey != null && !secretKey.isBlank()) {
                String v = secretKey.trim();
                try {
                    setting.setGhAkwapaySecretKey(GatewaySecretCrypto.encrypt(v, ks));
                } catch (Exception e) {
                    throw ApiException.badRequest("Could not store the secret key.");
                }
                setting.setGhAkwapaySecretKeyLast4(v.length() <= 4 ? v : v.substring(v.length() - 4));
                touched = true;
            }
            if (webhookSecret != null && !webhookSecret.isBlank()) {
                try {
                    setting.setGhAkwapayWebhookSecret(GatewaySecretCrypto.encrypt(webhookSecret.trim(), ks));
                } catch (Exception e) {
                    throw ApiException.badRequest("Could not store the webhook secret.");
                }
                touched = true;
            }
            if (!touched) {
                throw ApiException.badRequest("Enter a secret key, a webhook secret, or use Remove to go back to the platform key.");
            }
        }
        boolean finalSecret = setting.getGhAkwapaySecretKey() != null && !setting.getGhAkwapaySecretKey().isBlank();
        boolean finalWh = setting.getGhAkwapayWebhookSecret() != null && !setting.getGhAkwapayWebhookSecret().isBlank();
        if (finalSecret != finalWh) {
            throw ApiException.badRequest("Save both the secret key and the webhook secret together — a key without its webhook secret creates prompts that can never settle.");
        }
        setting.setUpdatedBy(sa.getId());
        setting.setUpdatedAt(Instant.now());
        var saved = settingRepo.save(setting);
        var after = ghanaKeyStatus(sa.getId());
        auditService.log(sa.getId(), "SET_GHANA_KEYS", "branch_gateway_settings", saved.getId(),
                Map.of("secretKeySet", before.get("secretKeySet"),
                        "secretKeyLast4", String.valueOf(before.get("secretKeyLast4")),
                        "webhookSecretSet", before.get("webhookSecretSet")),
                Map.of("secretKeySet", after.get("secretKeySet"),
                        "secretKeyLast4", String.valueOf(after.get("secretKeyLast4")),
                        "webhookSecretSet", after.get("webhookSecretSet")), null);
        return after;
    }

    /** Decrypted branch secret key, or empty when unset/unreadable. */
    public Optional<String> branchSecretKey(UUID saId) {
        try {
            if (gatewayKeySecret == null || gatewayKeySecret.isBlank()) return Optional.empty();
            var setting = settingRepo.findBySuperAdminId(saId).orElse(null);
            if (setting == null || setting.getGhAkwapaySecretKey() == null
                    || setting.getGhAkwapaySecretKey().isBlank()) return Optional.empty();
            return Optional.of(GatewaySecretCrypto.decrypt(setting.getGhAkwapaySecretKey(), gatewayKeySecret));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Decrypted branch webhook secret, or empty when unset/unreadable. */
    public Optional<String> branchWebhookSecret(UUID saId) {
        try {
            if (gatewayKeySecret == null || gatewayKeySecret.isBlank()) return Optional.empty();
            var setting = settingRepo.findBySuperAdminId(saId).orElse(null);
            if (setting == null || setting.getGhAkwapayWebhookSecret() == null
                    || setting.getGhAkwapayWebhookSecret().isBlank()) return Optional.empty();
            return Optional.of(GatewaySecretCrypto.decrypt(setting.getGhAkwapayWebhookSecret(), gatewayKeySecret));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Branch super admin for a user id (webhook routing has no principal). */
    public Optional<UUID> resolveBranchSuperAdminId(UUID userId) {
        if (userId == null) return Optional.empty();
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return Optional.empty();
        return resolveBranchSuperAdmin(user);
    }
    }

    public Map<String, Object> requestRowOrNull(UUID superAdminId) {
        return requestRepo.findBySuperAdminIdAndStatus(superAdminId, "PENDING")
                .map(this::requestRow).orElse(null);
    }

    public Map<String, Object> requestRow(GatewayRequest r) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", r.getId().toString());
        row.put("superAdminId", r.getSuperAdminId().toString());
        userRepo.findById(r.getSuperAdminId()).ifPresent(sa -> row.put("superAdminEmail", sa.getEmail()));
        row.put("ghGateway", r.getGhGateway());
        row.put("ngGateway", r.getNgGateway());
        row.put("status", r.getStatus());
        row.put("requestedAt", r.getRequestedAt() == null ? null : r.getRequestedAt().toString());
        row.put("decidedBy", r.getDecidedBy() == null ? null : r.getDecidedBy().toString());
        row.put("decidedAt", r.getDecidedAt() == null ? null : r.getDecidedAt().toString());
        return row;
    }

    // ─── Super admin decides (request only) ────────────────────────────

    @Transactional
    public GatewayRequest requestGateway(User sa, String gh, String ng) {
        if (sa == null || sa.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a super admin can request gateways for their branch.");
        }
        var ghV = requireGh(gh);
        var ngV = requireNg(ng);
        if (requestRepo.findBySuperAdminIdAndStatus(sa.getId(), "PENDING").isPresent()) {
            throw ApiException.badRequest("You already have a pending gateway request. It stays live as your current gateways until the site owner sets it — ask the owner to approve or reject it first.");
        }
        var req = GatewayRequest.builder()
                .superAdminId(sa.getId()).ghGateway(ghV).ngGateway(ngV)
                .status("PENDING").requestedAt(Instant.now()).build();
        var saved = requestRepo.save(req);
        auditService.log(sa.getId(), "REQUEST_BRANCH_GATEWAY", "gateway_requests", saved.getId(),
                null, Map.of("ghGateway", ghV, "ngGateway", ngV), null);
        return saved;
    }

    // ─── Site owner sets (approve / reject / direct) ───────────────────

    private BranchGatewaySetting applySetting(User owner, UUID saId, String gh, String ng, Map<String, Object> before) {
        var setting = settingRepo.findBySuperAdminId(saId)
                .orElseGet(() -> BranchGatewaySetting.builder()
                        .superAdminId(saId).ghGateway(DEFAULT_GH).ngGateway(DEFAULT_NG).build());
        if (gh != null) setting.setGhGateway(gh);
        if (ng != null) setting.setNgGateway(ng);
        setting.setUpdatedBy(owner.getId());
        setting.setUpdatedAt(Instant.now());
        var saved = settingRepo.save(setting);
        auditService.log(owner.getId(), "SET_BRANCH_GATEWAY", "branch_gateway_settings", saved.getId(),
                before, Map.of("ghGateway", saved.getGhGateway(), "ngGateway", saved.getNgGateway()), null);
        return saved;
    }

    private void supersedePending(User owner, UUID saId) {
        for (var pending : requestRepo.findAllBySuperAdminIdAndStatus(saId, "PENDING")) {
            pending.setStatus("REJECTED");
            pending.setDecidedBy(owner.getId());
            pending.setDecidedAt(Instant.now());
            requestRepo.save(pending);
        }
    }

    @Transactional
    public Map<String, Object> approveRequest(User owner, UUID requestId) {
        var req = requestRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Gateway request not found."));
        if (!"PENDING".equals(req.getStatus())) {
            throw ApiException.badRequest("This request was already decided: " + req.getStatus());
        }
        var before = activeFor(userRepo.findById(req.getSuperAdminId()).orElse(null));
        var setting = applySetting(owner, req.getSuperAdminId(), req.getGhGateway(), req.getNgGateway(), before);
        req.setStatus("APPROVED");
        req.setDecidedBy(owner.getId());
        req.setDecidedAt(Instant.now());
        requestRepo.save(req);
        auditService.log(owner.getId(), "APPROVE_GATEWAY_REQUEST", "gateway_requests", req.getId(),
                null, Map.of("ghGateway", req.getGhGateway(), "ngGateway", req.getNgGateway()), null);
        var out = new LinkedHashMap<String, Object>();
        out.put("request", requestRow(req));
        out.put("setting", settingRow(setting));
        return out;
    }

    @Transactional
    public Map<String, Object> rejectRequest(User owner, UUID requestId) {
        var req = requestRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Gateway request not found."));
        if (!"PENDING".equals(req.getStatus())) {
            throw ApiException.badRequest("This request was already decided: " + req.getStatus());
        }
        req.setStatus("REJECTED");
        req.setDecidedBy(owner.getId());
        req.setDecidedAt(Instant.now());
        requestRepo.save(req);
        auditService.log(owner.getId(), "REJECT_GATEWAY_REQUEST", "gateway_requests", req.getId(),
                null, Map.of("ghGateway", req.getGhGateway(), "ngGateway", req.getNgGateway()), null);
        return requestRow(req);
    }

    @Transactional
    public Map<String, Object> setDirect(User owner, UUID saId, String gh, String ng) {
        var sa = userRepo.findById(saId)
                .orElseThrow(() -> ApiException.notFound("Super admin not found."));
        if (sa.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.badRequest("That user is not a super admin.");
        }
        var ghV = optGh(gh);
        var ngV = optNg(ng);
        if (ghV == null && ngV == null) {
            throw ApiException.badRequest("Set at least one gateway (ghGateway or ngGateway).");
        }
        var before = activeFor(sa);
        // A direct owner set supersedes any pending request for this
        // branch, so a later approve can never resurrect the old choice.
        supersedePending(owner, saId);
        var setting = applySetting(owner, saId, ghV, ngV, before);
        return settingRow(setting);
    }

    // ─── Super admin sets their OWN branch gateways ─────────────────────

    /**
     * Direct super-admin write of their OWN branch's gateways — no site
     * owner approval. Ghana is AkwaPay only (the only Ghana gateway the
     * deposit page offers); Nigeria is MANUAL only (manual bank transfer
     * is the only Nigeria method the deposit page offers since checkout
     * was removed). At least one side must be supplied; the other side
     * keeps its current value. A pending gateway request is left alone:
     * the owner can still approve/reject it, and an approval would then
     * overwrite this direct set — the owner remains the final override.
     */
    @Transactional
    public Map<String, Object> setOwnGateways(User sa, String gh, String ng) {
        if (sa == null || sa.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a super admin can set gateways for their branch.");
        }
        String ghV = null, ngV = null;
        if (gh != null && !gh.isBlank()) {
            ghV = gh.trim().toUpperCase();
            if (!"AKWAPAY".equals(ghV)) {
                throw ApiException.badRequest("Ghana gateway is AkwaPay.");
            }
        }
        if (ng != null && !ng.isBlank()) {
            ngV = ng.trim().toUpperCase();
            if (!"MANUAL".equals(ngV)) {
                throw ApiException.badRequest("Nigeria gateway is manual bank transfer.");
            }
        }
        if (ghV == null && ngV == null) {
            throw ApiException.badRequest("Set at least one gateway (ghGateway or ngGateway).");
        }
        var setting = settingRepo.findBySuperAdminId(sa.getId())
                .orElseGet(() -> BranchGatewaySetting.builder()
                        .superAdminId(sa.getId()).ghGateway(DEFAULT_GH).ngGateway(DEFAULT_NG).build());
        var before = settingRow(setting);
        if (ghV != null) setting.setGhGateway(ghV);
        if (ngV != null) setting.setNgGateway(ngV);
        setting.setUpdatedBy(sa.getId());
        setting.setUpdatedAt(Instant.now());
        var saved = settingRepo.save(setting);
        auditService.log(sa.getId(), "SET_OWN_GATEWAYS", "branch_gateway_settings", saved.getId(),
                Map.of("ghGateway", before.get("ghGateway"), "ngGateway", before.get("ngGateway")),
                Map.of("ghGateway", saved.getGhGateway(), "ngGateway", saved.getNgGateway()), null);
        return settingRow(saved);
    }
}
