package com.speedbet.api.affiliate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily auto-settle for super-admin commission, gated by the owner's
 * toggle (see {@link SuperAdminPayoutSettings}).
 *
 * Runs at 00:30 UTC by default (configurable via
 * speedbet.superadmin.payout.auto.cron) — deliberately BEFORE the 01:00
 * UTC affiliate sweep, so super-admin balances are settled first and the
 * sweep then only handles admin balances.
 *
 * Scope honesty: this records payouts and clears balances; it does not
 * move MoMo/bank money. Only enable it when super admins are paid
 * through an automatic channel outside this system.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminPayoutAutoSettleScheduler {

    private final SuperAdminPayoutService payoutService;
    private final SuperAdminPayoutSettingsRepository settingsRepo;

    @Scheduled(cron = "${speedbet.superadmin.payout.auto.cron:0 30 0 * * *}", zone = "UTC")
    public void runAutoSettle() {
        var settings = settingsRepo.findById(1L).orElse(null);
        if (settings == null || !settings.isAutoSettleDaily()) {
            return;
        }
        log.info("SuperAdminPayoutAutoSettleScheduler: auto-settle enabled, paying all SA balances");
        try {
            var result = payoutService.payAll(null, "AUTO");
            log.info("SuperAdminPayoutAutoSettleScheduler: done — {}", result);
        } catch (Exception ex) {
            log.error("SuperAdminPayoutAutoSettleScheduler: failed — {}", ex.getMessage(), ex);
        }
    }
}
