package com.speedbet.api.superadmin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SuperAdminCommissionOperationsDtos {
    private SuperAdminCommissionOperationsDtos() {}

    public record DailyAdminCommissionDto(
            LocalDate date,
            UUID adminId,
            String adminEmail,
            String adminName,
            BigDecimal commissionPercent,
            BigDecimal commissionEarned,
            String commissionCurrency,
            BigDecimal commissionBalance,
            BigDecimal totalDeposits,
            long depositCount
    ) {}

    public record CommissionPayoutDto(
            UUID payoutId,
            UUID adminId,
            String adminEmail,
            BigDecimal amount,
            String currency,
            String reference,
            String status,
            java.time.Instant paidAt
    ) {}

    public record ReconcileCommissionDto(
            LocalDate date,
            UUID adminId,
            String adminEmail,
            BigDecimal commissionPercent,
            BigDecimal totalDeposits,
            BigDecimal expectedCommission,
            BigDecimal alreadyCredited,
            BigDecimal toppedUp,
            String currency,
            BigDecimal commissionBalance
    ) {}

    public record ClearCommissionResult(
            long adminsCleared,
            BigDecimal amountCleared,
            List<UUID> adminIds,
            java.time.Instant clearedAt
    ) {}

    /**
     * One depositor for an admin on one UTC day (aggregated per user).
     * {@code currency} is the depositor's own currency (GHS or NGN) and
     * {@code depositTotal} is in that original currency — amounts are never
     * converted or combined across currencies.
     */
    public record AdminDailyDepositorDto(
            UUID userId,
            String email,
            String firstName,
            String lastName,
            String country,
            String currency,
            BigDecimal depositTotal,
            long depositCount
    ) {}
}
