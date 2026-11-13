package com.speedbet.api.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The PLATFORM (general) gateway secret for one gateway, set by the site
 * owner in the app — no server access needed. One row per gateway name
 * ("ALPHAPAY", "RUSHPAY"). This is the platform fallback key: it is used
 * when a branch has NOT set its own key, and it sits between the branch
 * key and the Railway env key in the resolution order
 * (branch key -> platform DB key -> env key).
 *
 * The secret is encrypted with GatewaySecretCrypto (AES-GCM, key =
 * SHA-256 of GATEWAY_KEY_SECRET) before storage; only the last 4 are
 * ever returned. A decryption failure is treated as "no platform key"
 * and falls back to the env key — a bad stored value must never break
 * deposits. Deleting the row (clear) goes back to the env key.
 */
@Entity
@Table(name = "platform_gateway_keys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformGatewayKey {

    @Id
    @Column(name = "gateway", nullable = false, length = 30)
    private String gateway;

    @Column(name = "encrypted_secret_key", length = 500)
    private String encryptedSecretKey;

    @Column(name = "secret_key_last4", length = 10)
    private String secretKeyLast4;

    // RushPay's second half: the webhook secret that verifies settlement
    // webhooks (HMAC). Only used by the RUSHPAY row; null for other rows.
    @Column(name = "encrypted_webhook_secret", length = 500)
    private String encryptedWebhookSecret;

    @Column(name = "webhook_secret_last4", length = 10)
    private String webhookSecretLast4;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
