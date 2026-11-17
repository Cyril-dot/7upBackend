package com.speedbet.api.wallet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user-submitted Ghana manual MoMo deposit request
 * (HolyStar-style flow, v179).
 *
 * The user pays externally via *170# MoMoPay &amp; Pay Bill to the branch's
 * MoMoPay merchant ID, then submits the 11-digit MoMo Transaction ID plus a
 * receipt screenshot. The super-admin reviews the proof, verifies the
 * payment, then either APPROVES (credits the wallet) or REJECTS it.
 *
 * Lifecycle:  PENDING → APPROVED | REJECTED
 *
 * Mirrors BankDeposit (Nigeria manual bank transfer) field for field, with
 * GH₵ amounts and a MoMo transaction ID instead of a bank reference.
 *
 * NOTE on screenshotUrl: same as BankDeposit — the frontend uploads the
 * payment screenshot to ImgBB and sends only the returned link in the JSON
 * body, so TEXT is more than enough here (a hosted URL, not a data-URL).
 * Kept as TEXT for parity and safety.
 */
@Entity
@Table(
        name = "momo_deposits",
        indexes = {
                @Index(name = "idx_mmd_user_id",        columnList = "user_id"),
                @Index(name = "idx_mmd_transaction_id", columnList = "transaction_id", unique = true),
                @Index(name = "idx_mmd_status",         columnList = "status"),
                @Index(name = "idx_mmd_reviewed_by",    columnList = "reviewed_by")
        }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MomoDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Who submitted ────────────────────────────────────────────────────────
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ── Payment proof fields ─────────────────────────────────────────────────

    /** 11-digit MoMo Transaction ID from the *170# payment */
    @Column(name = "transaction_id", nullable = false, length = 32)
    private String transactionId;

    /** Amount the user claims to have sent (GH₵) */
    @Column(name = "ghs_amount_sent", nullable = false, precision = 18, scale = 2)
    private BigDecimal ghsAmountSent;

    /** GH₵ equivalent the user expects to be credited (admin can override) */
    @Column(name = "expected_ghs_credit", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedGhsCredit;

    /** Actual GH₵ amount credited — set by admin on approval */
    @Column(name = "credited_ghs_amount", precision = 18, scale = 2)
    private BigDecimal creditedGhsAmount;

    /** Name on the sender's MoMo account */
    @Column(name = "sender_name", length = 256)
    private String senderName;

    /** Payment receipt screenshot (ImgBB hosted URL from the frontend) */
    @Column(name = "screenshot_url", columnDefinition = "TEXT")
    private String screenshotUrl;

    /** Optional free-text note from the user */
    @Column(name = "user_note", length = 1000)
    private String userNote;

    // ── Status ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private MomoDepositStatus status = MomoDepositStatus.PENDING;

    // ── Admin review fields ──────────────────────────────────────────────────

    /** UUID of the super-admin who reviewed this deposit */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Admin's internal note (rejection reason, override explanation, etc.) */
    @Column(name = "admin_note", length = 1000)
    private String adminNote;

    /**
     * The wallet Transaction row created when this deposit was approved.
     * Null until approval.
     */
    @Column(name = "wallet_transaction_id")
    private UUID walletTransactionId;

    // ── Audit timestamps ─────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
