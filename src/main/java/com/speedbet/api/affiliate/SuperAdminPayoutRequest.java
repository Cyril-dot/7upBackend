package com.speedbet.api.affiliate;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A commission payout request submitted by a SUPER ADMIN to the site owner.
 *
 * This is the super-admin half of the payout system. The existing
 * {@link PayoutRequest} covers ADMIN affiliates requesting from their
 * super admin; this entity covers SUPER ADMINS requesting from the site
 * owner, which previously had no request flow at all (the owner could only
 * bulk-clear a day's commission).
 *
 * Lifecycle: REQUESTED → APPROVED → PAID
 *                       ↘ REJECTED
 *
 * The amount is a snapshot of the super admin's GHS commission balance at
 * request time (commission is GHS-denominated by design: ReferralService
 * converts non-GHS commission to GHS on credit). Approval alone moves no
 * money and touches no balance; only mark-paid debits the balance, and it
 * refuses if the balance no longer covers the snapshot (e.g. the 01:00 UTC
 * affiliate sweep already processed it) so an SA can never be paid twice
 * for the same commission.
 *
 * Money movement itself happens off-platform (the owner sends MoMo/bank
 * transfer, as with today's Settle flow); mark-paid is the owner's
 * confirmation that the transfer was sent, and it is what clears the
 * balance in the system.
 */
@Entity
@Table(name = "super_admin_payout_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SuperAdminPayoutRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The super admin requesting payout of their own commission. */
    @Column(name = "super_admin_id", nullable = false)
    private UUID superAdminId;

    /** Snapshot of the commission balance at request time. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 8)
    @Builder.Default
    private String currency = "GHS";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PayoutStatus status = PayoutStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
