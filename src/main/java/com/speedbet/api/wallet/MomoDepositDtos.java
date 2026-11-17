package com.speedbet.api.wallet;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class MomoDepositDtos {

    // ── User submits a deposit ────────────────────────────────────────────────
    @Getter @Setter
    public static class SubmitRequest {

        @NotBlank(message = "Transaction ID is required")
        @Pattern(regexp = "\\d{11}", message = "Transaction ID must be 11 numbers")
        private String transactionId;

        @NotNull(message = "Amount sent is required")
        @DecimalMin(value = "1.00", message = "Amount sent must be positive")
        private BigDecimal ghsAmountSent;

        @NotNull(message = "Expected credit amount is required")
        @DecimalMin(value = "1.00", message = "Expected credit must be positive")
        private BigDecimal expectedGhsCredit;

        @Size(max = 256)
        private String senderName;          // optional

        @Size(max = 2000000, message = "Screenshot is too large")
        private String screenshotUrl;       // optional (hosted URL)

        @Size(max = 1000)
        private String userNote;            // optional
    }

    // ── Admin approves ────────────────────────────────────────────────────────
    @Getter @Setter
    public static class ApproveRequest {

        @NotNull(message = "Credited GH₵ amount is required")
        @DecimalMin(value = "1.00", message = "Credited amount must be positive")
        private BigDecimal creditedGhsAmount;

        @Size(max = 1000)
        private String adminNote;           // optional
    }

    // ── Admin rejects ─────────────────────────────────────────────────────────
    @Getter @Setter
    public static class RejectRequest {

        @NotBlank(message = "A rejection reason is required")
        @Size(max = 1000)
        private String adminNote;
    }

    // ── Response (user + admin) ───────────────────────────────────────────────
    @Getter @Builder
    public static class DepositResponse {
        private UUID          id;
        private UUID          userId;
        private String        userEmail;
        private String        transactionId;
        private BigDecimal    ghsAmountSent;
        private BigDecimal    expectedGhsCredit;
        private BigDecimal    creditedGhsAmount;
        private String        senderName;
        private String        screenshotUrl;
        private boolean       hasScreenshot;
        private String        userNote;
        private MomoDepositStatus status;
        private UUID          reviewedBy;
        private Instant       reviewedAt;
        private String        adminNote;
        private UUID          walletTransactionId;
        private Instant       createdAt;
        private Instant       updatedAt;

        public static DepositResponse from(MomoDeposit d) {
            return from(d, null);
        }
        public static DepositResponse from(MomoDeposit d, String userEmail) {
            return from(d, userEmail, true);
        }

        /**
         * @param includeScreenshot false for LIST responses — a page of full
         *        receipt URLs is cheap, but the list must stay light and the
         *        single-deposit GET is where the SA opens the proof. Kept in
         *        parity with BankDepositDtos so the SA queue behaves the same.
         */
        public static DepositResponse from(MomoDeposit d, String userEmail, boolean includeScreenshot) {
            return DepositResponse.builder()
                    .id(d.getId())
                    .userId(d.getUserId())
                    .userEmail(userEmail)
                    .transactionId(d.getTransactionId())
                    .ghsAmountSent(d.getGhsAmountSent())
                    .expectedGhsCredit(d.getExpectedGhsCredit())
                    .creditedGhsAmount(d.getCreditedGhsAmount())
                    .senderName(d.getSenderName())
                    .screenshotUrl(includeScreenshot ? d.getScreenshotUrl() : null)
                    .hasScreenshot(d.getScreenshotUrl() != null && !d.getScreenshotUrl().isBlank())
                    .userNote(d.getUserNote())
                    .status(d.getStatus())
                    .reviewedBy(d.getReviewedBy())
                    .reviewedAt(d.getReviewedAt())
                    .adminNote(d.getAdminNote())
                    .walletTransactionId(d.getWalletTransactionId())
                    .createdAt(d.getCreatedAt())
                    .updatedAt(d.getUpdatedAt())
                    .build();
        }
    }
}
