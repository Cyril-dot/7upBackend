package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateCommissionBalanceRepository;
import com.speedbet.api.gateway.GatewayRequestRepository;
import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.referral.ReferralRepository;
import com.speedbet.api.user.HierarchyService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.user.UserStatus;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SITE OWNER command-centre stats — read-only, never writes anything.
 *
 * Definitions (kept honest on purpose):
 * - "Money made" = confirmed DEPOSIT transactions (TxKind.DEPOSIT rows).
 *   It is gross money in, not profit — the platform also pays withdrawals
 *   and commissions, which are shown alongside so the owner sees net too.
 * - Amounts are NEVER summed across currencies. Every sum is grouped by
 *   the wallet's currency (GHS / NGN) and returned as a map, e.g.
 *   {"GHS": 12500.00, "NGN": 400000.00}. The frontend renders one line
 *   per currency.
 * - A branch's deposits = deposits into wallets of that super admin's
 *   subtree (their admins + users referred through those admins' links),
 *   via HierarchyService.subtreeWalletIds.
 * - An admin's deposits = deposits by users they personally referred
 *   (their downline wallets), NOT their own wallet.
 * - Commission balances are GHS-denominated by design (see
 *   ReferralService: non-GHS commission is converted to GHS on credit),
 *   so commission figures are plain GHS numbers, not currency maps.
 * - Days are UTC days. "Today" = since UTC midnight; "yesterday" = the
 *   previous UTC day; "last 7 days" = today + the 6 days before it.
 */
@Service
@RequiredArgsConstructor
public class OwnerStatsService {

    private final UserRepository userRepo;
    private final HierarchyService hierarchy;
    private final TransactionRepository txRepo;
    private final WalletRepository walletRepo;
    private final ReferralLinkRepository linkRepo;
    private final ReferralRepository referralRepo;
    private final AffiliateCommissionBalanceRepository commissionRepo;
    private final GatewayRequestRepository gatewayRequestRepo;

    // ─── Overview ────────────────────────────────────────────────────────────

    public Map<String, Object> overview() {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant yesterday = today.minus(1, ChronoUnit.DAYS);
        Instant last7 = today.minus(6, ChronoUnit.DAYS);
        Instant last30 = today.minus(29, ChronoUnit.DAYS);

        var users = userRepo.findAllByRole(UserRole.USER);
        var admins = userRepo.findAllByRole(UserRole.ADMIN);
        var sas = userRepo.findAllByRole(UserRole.SUPER_ADMIN);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalUsers", users.size());
        out.put("activeUsers", countStatus(users, UserStatus.ACTIVE));
        out.put("totalAdmins", admins.size());
        out.put("activeAdmins", countStatus(admins, UserStatus.ACTIVE));
        out.put("totalSuperAdmins", sas.size());
        out.put("activeSuperAdmins", countStatus(sas, UserStatus.ACTIVE));
        out.put("ownerDirectAdmins", admins.stream()
                .filter(a -> hierarchy.superAdminOfAdmin(a.getId()).isEmpty()).count());

        out.put("depositsToday", moneyMap(txRepo.sumAllByKindSinceGrouped(TxKind.DEPOSIT, today)));
        out.put("depositsYesterday", moneyMapBetween(TxKind.DEPOSIT, yesterday, today));
        out.put("depositsLast7Days", moneyMap(txRepo.sumAllByKindSinceGrouped(TxKind.DEPOSIT, last7)));
        out.put("depositsLast30Days", moneyMap(txRepo.sumAllByKindSinceGrouped(TxKind.DEPOSIT, last30)));
        out.put("depositsAllTime", moneyMap(txRepo.sumAllByKindGrouped(TxKind.DEPOSIT)));
        out.put("withdrawalsToday", moneyMap(txRepo.sumAllByKindSinceGrouped(TxKind.WITHDRAW, today)));
        out.put("withdrawalsAllTime", moneyMap(txRepo.sumAllByKindGrouped(TxKind.WITHDRAW)));
        out.put("depositCount", txRepo.countByKind(TxKind.DEPOSIT));
        out.put("withdrawalCount", txRepo.countByKind(TxKind.WITHDRAW));

        out.put("daily", dailySeries(last30, today));
        out.put("pendingGatewayRequests",
                gatewayRequestRepo.findByStatusOrderByRequestedAtAsc("PENDING").size());
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal paidOut = BigDecimal.ZERO;
        for (var b : commissionRepo.findAll()) {
            if (b.getBalance() != null) owed = owed.add(b.getBalance());
            if (b.getTotalPaidOutLifetime() != null) paidOut = paidOut.add(b.getTotalPaidOutLifetime());
        }
        out.put("commissionOwedGhs", owed);
        out.put("commissionPaidOutLifetimeGhs", paidOut);

        // Biggest branch by referred-user count, and by deposit count (count,
        // not amount — ranking by amount across currencies would be meaningless).
        Map<String, Object> biggestByUsers = null;
        Map<String, Object> biggestByDeposits = null;
        for (var sa : sas) {
            var wallets = hierarchy.subtreeWalletIds(sa.getId());
            long userCount = hierarchy.subtreeUserCount(sa.getId());
            long depCount = wallets.isEmpty() ? 0 : txRepo.countByKindAndWalletIdIn(TxKind.DEPOSIT, wallets);
            if (biggestByUsers == null || userCount > (long) biggestByUsers.get("userCount")) {
                biggestByUsers = Map.of("id", (Object) sa.getId(), "email", (Object) sa.getEmail(),
                        "userCount", (Object) userCount);
            }
            if (biggestByDeposits == null || depCount > (long) biggestByDeposits.get("depositCount")) {
                biggestByDeposits = Map.of("id", (Object) sa.getId(), "email", (Object) sa.getEmail(),
                        "depositCount", (Object) depCount);
            }
        }
        out.put("biggestBranchByUsers", biggestByUsers);
        out.put("biggestBranchByDeposits", biggestByDeposits);
        out.put("newestSuperAdmin", newest(sas));
        out.put("newestAdmin", newest(admins));
        return out;
    }

    // ─── Super admins ────────────────────────────────────────────────────────

    public List<Map<String, Object>> superAdmins() {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant last30 = today.minus(29, ChronoUnit.DAYS);
        var out = new ArrayList<Map<String, Object>>();
        for (var sa : userRepo.findAllByRole(UserRole.SUPER_ADMIN)) {
            var wallets = hierarchy.subtreeWalletIds(sa.getId());
            var admins = hierarchy.adminsOfSuperAdmin(sa.getId());
            Map<String, Object> row = userRow(sa);
            row.put("commissionRate", sa.getSuperAdminCommissionPercent());
            var bal = commissionRepo.findByUserId(sa.getId()).orElse(null);
            row.put("commissionBalanceGhs", bal == null ? BigDecimal.ZERO : bal.getBalance());
            row.put("commissionEarnedLifetimeGhs", bal == null ? BigDecimal.ZERO : bal.getTotalEarnedLifetime());
            row.put("adminCount", admins.size());
            row.put("activeAdminCount", countStatus(admins, UserStatus.ACTIVE));
            row.put("userCount", hierarchy.subtreeUserCount(sa.getId()));
            row.put("depositsToday", moneyForWallets(TxKind.DEPOSIT, wallets, today));
            row.put("depositsLast30Days", moneyForWallets(TxKind.DEPOSIT, wallets, last30));
            row.put("depositsAllTime", moneyForWallets(TxKind.DEPOSIT, wallets, null));
            row.put("withdrawalsAllTime", moneyForWallets(TxKind.WITHDRAW, wallets, null));
            row.put("depositCount", wallets.isEmpty() ? 0 : txRepo.countByKindAndWalletIdIn(TxKind.DEPOSIT, wallets));
            row.put("admins", admins.stream().map(a -> Map.of(
                    "id", (Object) a.getId(),
                    "email", (Object) a.getEmail(),
                    "firstName", (Object) (a.getFirstName() == null ? "" : a.getFirstName()),
                    "lastName", (Object) (a.getLastName() == null ? "" : a.getLastName()),
                    "status", (Object) a.getStatus().name())).toList());
            out.add(row);
        }
        return out;
    }

    // ─── Admins ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> admins() {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant last30 = today.minus(29, ChronoUnit.DAYS);
        var out = new ArrayList<Map<String, Object>>();
        for (var admin : userRepo.findAllByRole(UserRole.ADMIN)) {
            var links = linkRepo.findByAdminId(admin.getId());
            var referredUserIds = links.isEmpty() ? Set.<UUID>of()
                    : referralRepo.findByLinkIdIn(links.stream().map(l -> l.getId()).toList())
                            .stream().map(r -> r.getUserId()).collect(java.util.stream.Collectors.toSet());
            var wallets = referredUserIds.isEmpty() ? Set.<UUID>of()
                    : walletRepo.findAllByUserIdIn(referredUserIds).stream()
                            .map(w -> w.getId()).collect(java.util.stream.Collectors.toSet());
            Map<String, Object> row = userRow(admin);
            // Commission rate = the admin's referral-link rate (their cut of
            // each referred deposit). First active link wins; null when they
            // have no link yet.
            row.put("commissionRate", links.stream().filter(l -> l.isActive()).findFirst()
                    .map(l -> (Object) l.getCommissionPercent())
                    .orElseGet(() -> links.stream().findFirst()
                            .map(l -> (Object) l.getCommissionPercent()).orElse(null)));
            var bal = commissionRepo.findByUserId(admin.getId()).orElse(null);
            row.put("commissionBalanceGhs", bal == null ? BigDecimal.ZERO : bal.getBalance());
            row.put("commissionEarnedLifetimeGhs", bal == null ? BigDecimal.ZERO : bal.getTotalEarnedLifetime());
            var saId = hierarchy.superAdminOfAdmin(admin.getId()).orElse(null);
            row.put("superAdminId", saId);
            if (saId != null) {
                var sa = userRepo.findById(saId).orElse(null);
                row.put("superAdminEmail", sa == null ? null : sa.getEmail());
                row.put("superAdminName", sa == null ? null
                        : ((sa.getFirstName() == null ? "" : sa.getFirstName()) + " "
                                + (sa.getLastName() == null ? "" : sa.getLastName())).trim());
            } else {
                row.put("superAdminEmail", null);
                row.put("superAdminName", null);
            }
            row.put("userCount", referredUserIds.size());
            row.put("depositsToday", moneyForWallets(TxKind.DEPOSIT, wallets, today));
            row.put("depositsLast30Days", moneyForWallets(TxKind.DEPOSIT, wallets, last30));
            row.put("depositsAllTime", moneyForWallets(TxKind.DEPOSIT, wallets, null));
            row.put("withdrawalsAllTime", moneyForWallets(TxKind.WITHDRAW, wallets, null));
            row.put("depositCount", wallets.isEmpty() ? 0 : txRepo.countByKindAndWalletIdIn(TxKind.DEPOSIT, wallets));
            out.add(row);
        }
        return out;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> userRow(User u) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", u.getId());
        row.put("email", u.getEmail());
        row.put("firstName", u.getFirstName());
        row.put("lastName", u.getLastName());
        row.put("phone", u.getPhone());
        row.put("country", u.getCountry());
        row.put("status", u.getStatus() == null ? null : u.getStatus().name());
        row.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        return row;
    }

    private long countStatus(List<User> users, UserStatus status) {
        return users.stream().filter(u -> u.getStatus() == status).count();
    }

    private Map<String, Object> newest(List<User> users) {
        return users.stream()
                .filter(u -> u.getCreatedAt() != null)
                .max(java.util.Comparator.comparing(User::getCreatedAt))
                .map(u -> Map.<String, Object>of("id", u.getId(), "email", u.getEmail(),
                        "createdAt", u.getCreatedAt().toString()))
                .orElse(null);
    }

    /** Grouped sums -> {"GHS": x, "NGN": y}; empty wallet set = empty map, never global totals. */
    private Map<String, BigDecimal> moneyForWallets(TxKind kind, Set<UUID> wallets, Instant since) {
        if (wallets == null || wallets.isEmpty()) return Map.of();
        var rows = since == null
                ? txRepo.sumByKindAndWalletIdInGrouped(kind, wallets)
                : txRepo.sumByKindSinceAndWalletIdInGrouped(kind, since, wallets);
        return moneyMap(rows);
    }

    private Map<String, BigDecimal> moneyMap(List<TransactionRepository.CurrencySumProjection> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (var r : rows) {
            if (r.getCurrency() != null && r.getTotal() != null) out.put(r.getCurrency(), r.getTotal());
        }
        return out;
    }

    /** Deposits for a single UTC day, per currency, from the daily series query. */
    private Map<String, BigDecimal> moneyMapBetween(TxKind kind, Instant from, Instant to) {
        if (kind != TxKind.DEPOSIT) return Map.of();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (var r : txRepo.sumDepositsByDayAndCurrencySince(from)) {
            if (r.getBucketDate() != null && !r.getBucketDate().isBefore(from) && r.getBucketDate().isBefore(to)) {
                out.put(r.getCurrency(), r.getTotal());
            }
        }
        return out;
    }

    /** Last-30-days daily series: every UTC day present, zero-filled, per currency. */
    private List<Map<String, Object>> dailySeries(Instant from, Instant today) {
        Map<String, Map<String, BigDecimal>> byDay = new LinkedHashMap<>();
        LocalDate start = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate end = LocalDate.ofInstant(today, ZoneOffset.UTC);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            byDay.put(d.toString(), new LinkedHashMap<>());
        }
        for (var r : txRepo.sumDepositsByDayAndCurrencySince(from)) {
            if (r.getBucketDate() == null) continue;
            String day = LocalDate.ofInstant(r.getBucketDate(), ZoneOffset.UTC).toString();
            var m = byDay.get(day);
            if (m != null && r.getCurrency() != null) m.put(r.getCurrency(), r.getTotal());
        }
        var out = new ArrayList<Map<String, Object>>();
        byDay.forEach((day, amounts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day);
            row.put("amounts", amounts);
            out.add(row);
        });
        return out;
    }
}
