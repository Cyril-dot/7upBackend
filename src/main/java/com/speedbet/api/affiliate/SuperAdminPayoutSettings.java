package com.speedbet.api.affiliate;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row owner setting for super-admin commission payouts.
 * Row id is always 1; the row is created on first read if absent.
 *
 * autoSettleDaily: when true, the 00:30 UTC scheduler pays + clears every
 * super admin's current commission balance (skipping SAs with an open
 * payout request, whose money is handled through that request). It runs
 * BEFORE the 01:00 UTC affiliate sweep so SA balances are settled first
 * and the sweep then only handles admin balances.
 *
 * Honest scope note: "auto-pay" here means the system records the payout
 * and clears the balance. It does not move MoMo/bank money by itself —
 * enable it only if super admins are paid through an automatic channel
 * outside this system; otherwise use request → approve → mark-paid, where
 * mark-paid is the owner's confirmation that the transfer was sent.
 */
@Entity
@Table(name = "super_admin_payout_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SuperAdminPayoutSettings {

    @Id
    private Long id;

    @Column(name = "auto_settle_daily", nullable = false)
    @Builder.Default
    private boolean autoSettleDaily = false;

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
