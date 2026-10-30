package com.speedbet.api.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The ACTIVE payment gateways for one super admin's branch.
 *
 * The site owner writes this row (approve a super admin's request, or
 * set a branch directly), and a super admin writes their OWN row too:
 * their branch gateways (direct set) and their own AkwaPay credentials
 * for Ghana deposits. A super admin's gateway REQUEST still lives in
 * GatewayRequest and has NO effect on deposits until the owner
 * approves it.
 *
 * Gateways are plain strings, deliberately NOT a Java enum: a new
 * gateway value must never be blocked by a database check constraint
 * (see the users_role_check lesson — ddl-auto=update does not widen
 * pre-existing constraints). GatewayService validates every value
 * against the allowed lists before it is stored.
 */
@Entity
@Table(name = "branch_gateway_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BranchGatewaySetting {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "super_admin_id", nullable = false, unique = true)
    private UUID superAdminId;

    /** Ghana deposit gateway. Allowed: AKWAPAY, GMP. */
    @Column(name = "gh_gateway", nullable = false, length = 30)
    @Builder.Default
    private String ghGateway = "AKWAPAY";

    /** Nigeria deposit gateway. Allowed: GMP, KORAPAY. */
    @Column(name = "ng_gateway", nullable = false, length = 30)
    @Builder.Default
    private String ngGateway = "GMP";

    /**
     * Nigeria MANUAL bank details, set directly by the branch's super admin
     * (see GatewayService.setNgManualBank). These are the super admin's OWN
     * receiving bank details, shown to Nigerian users as the manual bank
     * transfer option on the deposit page — they are NOT a platform gateway
     * choice, so unlike ghGateway/ngGateway they need no site-owner approval.
     * All three are null when the super admin has not set them, and the
     * manual option is hidden from users until all three are present.
     * Nullable columns only, so ddl-auto=update adds them safely.
     */
    @Column(name = "ng_manual_bank_name", length = 100)
    private String ngManualBankName;

    @Column(name = "ng_manual_account_number", length = 50)
    private String ngManualAccountNumber;

    @Column(name = "ng_manual_account_name", length = 100)
    private String ngManualAccountName;

    // ── Ghana: this branch's OWN AkwaPay credentials (optional) ─────────
    // Encrypted with GatewaySecretCrypto (server env GATEWAY_KEY_SECRET).
    // When set, Ghana deposits from users on this branch run on the super
    // admin's own AkwaPay merchant account instead of the platform key.
    // The webhook secret is what verifies settlement webhooks from that
    // merchant, so both halves are needed: a key without its webhook
    // secret would create prompts that can never settle. Blank = fall
    // back to the platform primary key. Only the last 4 of the secret
    // key are ever returned to the frontend, never the values.
    @Column(name = "gh_akwapay_secret_key", length = 500)
    private String ghAkwapaySecretKey;

    @Column(name = "gh_akwapay_secret_key_last4", length = 10)
    private String ghAkwapaySecretKeyLast4;

    @Column(name = "gh_akwapay_webhook_secret", length = 500)
    private String ghAkwapayWebhookSecret;

    /** The site owner who last set this row. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", updatable = false, nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
