package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateWithdrawalRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalStatus;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.wallet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminQueryService {

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final ReferralLinkRepository referralLinkRepo;

    // ─── Revenue / Deposit Overview ───────────────────────────────────────────

    /**
     * NOTE: this sums every deposit into one figure and labels it GHS. If
     * Nigerian deposits are recorded in naira, that total mixes currencies.
     * The country-split endpoints under /commission/country-report are the
     * correct source for per-currency figures; this overview is kept for the
     * legacy dashboard tiles.
     */
    public SuperAdminDtos.RevenueOverviewDto getRevenueOverview() {
        log.info("getRevenueOverview: computing platform-wide stats");

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        BigDecimal depositsAllTime    = txRepo.sumAllByKind(TxKind.DEPOSIT);
        BigDecimal depositsThisMonth  = txRepo.sumAllByKindSince(TxKind.DEPOSIT, startOfMonth);
        BigDecimal depositsToday      = txRepo.sumAllByKindSince(TxKind.DEPOSIT, startOfToday);
        BigDecimal withdrawalsAllTime = txRepo.sumAllByKind(TxKind.WITHDRAW);
        BigDecimal withdrawalsMonth   = txRepo.sumAllByKindSince(TxKind.WITHDRAW, startOfMonth);
        long depositCount             = txRepo.countByKind(TxKind.DEPOSIT);
        long withdrawalCount          = txRepo.countByKind(TxKind.WITHDRAW);

        return new SuperAdminDtos.RevenueOverviewDto(
                depositsAllTime, depositsThisMonth, depositsToday,
                withdrawalsAllTime, withdrawalsMonth,
                depositCount, withdrawalCount, "GHS");
    }

    /**
     * Scoped twin of {@link #getRevenueOverview()}: sums/counts only over
     * transactions whose wallet belongs to the given set (a super admin's
     * subtree wallets). Empty set = all zeros, never the global total.
     */
    public SuperAdminDtos.RevenueOverviewDto getRevenueOverview(Set<UUID> allowedWalletIds) {
        log.info("getRevenueOverview scoped: wallets={}",
                allowedWalletIds == null ? null : allowedWalletIds.size());
        if (allowedWalletIds == null || allowedWalletIds.isEmpty()) {
            return new SuperAdminDtos.RevenueOverviewDto(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, "GHS");
        }

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        BigDecimal depositsAllTime    = txRepo.sumByKindAndWalletIdIn(TxKind.DEPOSIT, allowedWalletIds);
        BigDecimal depositsThisMonth  = txRepo.sumByKindSinceAndWalletIdIn(TxKind.DEPOSIT, startOfMonth, allowedWalletIds);
        BigDecimal depositsToday      = txRepo.sumByKindSinceAndWalletIdIn(TxKind.DEPOSIT, startOfToday, allowedWalletIds);
        BigDecimal withdrawalsAllTime = txRepo.sumByKindAndWalletIdIn(TxKind.WITHDRAW, allowedWalletIds);
        BigDecimal withdrawalsMonth   = txRepo.sumByKindSinceAndWalletIdIn(TxKind.WITHDRAW, startOfMonth, allowedWalletIds);
        long depositCount             = txRepo.countByKindAndWalletIdIn(TxKind.DEPOSIT, allowedWalletIds);
        long withdrawalCount          = txRepo.countByKindAndWalletIdIn(TxKind.WITHDRAW, allowedWalletIds);

        return new SuperAdminDtos.RevenueOverviewDto(
                depositsAllTime, depositsThisMonth, depositsToday,
                withdrawalsAllTime, withdrawalsMonth,
                depositCount, withdrawalCount, "GHS");
    }

    /**
     * Country-split revenue overview — the fix for the dashboard mixing GH and
     * NG deposits into one figure. Classifies every DEPOSIT and WITHDRAW
     * transaction into GH or NG using CountryUtils.classifyForRevenue:
     *   1. The depositing user's own country wins if it resolves to GH or NG,
     *      regardless of amount.
     *   2. Otherwise (user's country is OTHER/UNKNOWN), falls back to the
     *      amount heuristic: amount < 30,000 => GH, amount >= 30,000 => NG.
     * This applies uniformly to every deposit source that lands in the
     * Transaction table (wallet top-ups, approved bank transfers, approved
     * crypto deposits, approved simple/MoMo deposits) — all of them credit
     * the wallet via WalletService.credit(...), which is what writes the
     * DEPOSIT transaction row this method reads.
     * <p>
     * GH and NG totals are computed completely independently and are never
     * added together — there is deliberately no combined "platform total"
     * field on {@link SuperAdminDtos.CountryRevenueOverviewDto}.
     */
    public SuperAdminDtos.CountryRevenueOverviewDto getCountryRevenueOverview() {
        log.info("getCountryRevenueOverview: computing GH/NG split revenue stats");

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        List<SuperAdminDtos.RevenueRawRow> depositRows  = txRepo.findRawRowsByKind(TxKind.DEPOSIT);
        List<SuperAdminDtos.RevenueRawRow> withdrawRows = txRepo.findRawRowsByKind(TxKind.WITHDRAW);

        CountryAccumulator gh = new CountryAccumulator();
        CountryAccumulator ng = new CountryAccumulator();

        for (SuperAdminDtos.RevenueRawRow row : depositRows) {
            String classified = CountryUtils.classifyForRevenue(row.rawCountry(), row.amount());
            CountryAccumulator target = CountryUtils.GH.equals(classified) ? gh : ng;
            target.addDeposit(row.amount(), row.createdAt(), startOfMonth, startOfToday);
        }

        for (SuperAdminDtos.RevenueRawRow row : withdrawRows) {
            String classified = CountryUtils.classifyForRevenue(row.rawCountry(), row.amount());
            CountryAccumulator target = CountryUtils.GH.equals(classified) ? gh : ng;
            target.addWithdrawal(row.amount(), row.createdAt(), startOfMonth);
        }

        var ghanaBlock = new SuperAdminDtos.CountryRevenueBlockDto(
                CountryUtils.GH, "GHS",
                gh.depositsAllTime, gh.depositsThisMonth, gh.depositsToday,
                gh.withdrawalsAllTime, gh.withdrawalsThisMonth,
                gh.depositCount, gh.withdrawalCount);

        var nigeriaBlock = new SuperAdminDtos.CountryRevenueBlockDto(
                CountryUtils.NG, "NGN",
                ng.depositsAllTime, ng.depositsThisMonth, ng.depositsToday,
                ng.withdrawalsAllTime, ng.withdrawalsThisMonth,
                ng.depositCount, ng.withdrawalCount);

        return new SuperAdminDtos.CountryRevenueOverviewDto(ghanaBlock, nigeriaBlock);
    }

    /**
     * Scoped twin of {@link #getCountryRevenueOverview()}: only transactions
     * whose wallet is in {@code allowedWalletIds} are bucketed. Same
     * GH/NG classification, same shape — just a subtree's numbers.
     */
    public SuperAdminDtos.CountryRevenueOverviewDto getCountryRevenueOverview(Set<UUID> allowedWalletIds) {
        log.info("getCountryRevenueOverview scoped: wallets={}",
                allowedWalletIds == null ? null : allowedWalletIds.size());
        if (allowedWalletIds == null || allowedWalletIds.isEmpty()) {
            var zero = new SuperAdminDtos.CountryRevenueBlockDto(
                    CountryUtils.GH, "GHS",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);
            var zeroNg = new SuperAdminDtos.CountryRevenueBlockDto(
                    CountryUtils.NG, "NGN",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L);
            return new SuperAdminDtos.CountryRevenueOverviewDto(zero, zeroNg);
        }

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        List<SuperAdminDtos.RevenueRawRow> depositRows  = txRepo.findRawRowsByKind(TxKind.DEPOSIT).stream()
                .filter(r -> r.walletId() != null && allowedWalletIds.contains(r.walletId())).toList();
        List<SuperAdminDtos.RevenueRawRow> withdrawRows = txRepo.findRawRowsByKind(TxKind.WITHDRAW).stream()
                .filter(r -> r.walletId() != null && allowedWalletIds.contains(r.walletId())).toList();

        CountryAccumulator gh = new CountryAccumulator();
        CountryAccumulator ng = new CountryAccumulator();

        for (SuperAdminDtos.RevenueRawRow row : depositRows) {
            String classified = CountryUtils.classifyForRevenue(row.rawCountry(), row.amount());
            CountryAccumulator target = CountryUtils.GH.equals(classified) ? gh : ng;
            target.addDeposit(row.amount(), row.createdAt(), startOfMonth, startOfToday);
        }

        for (SuperAdminDtos.RevenueRawRow row : withdrawRows) {
            String classified = CountryUtils.classifyForRevenue(row.rawCountry(), row.amount());
            CountryAccumulator target = CountryUtils.GH.equals(classified) ? gh : ng;
            target.addWithdrawal(row.amount(), row.createdAt(), startOfMonth);
        }

        var ghanaBlock = new SuperAdminDtos.CountryRevenueBlockDto(
                CountryUtils.GH, "GHS",
                gh.depositsAllTime, gh.depositsThisMonth, gh.depositsToday,
                gh.withdrawalsAllTime, gh.withdrawalsThisMonth,
                gh.depositCount, gh.withdrawalCount);
        var nigeriaBlock = new SuperAdminDtos.CountryRevenueBlockDto(
                CountryUtils.NG, "NGN",
                ng.depositsAllTime, ng.depositsThisMonth, ng.depositsToday,
                ng.withdrawalsAllTime, ng.withdrawalsThisMonth,
                ng.depositCount, ng.withdrawalCount);

        return new SuperAdminDtos.CountryRevenueOverviewDto(ghanaBlock, nigeriaBlock);
    }

    /**
     * Mutable running totals for one country while iterating raw rows in
     * {@link #getCountryRevenueOverview()}. Kept private to this service —
     * never exposed outside that method, never touches the database itself.
     */
    private static final class CountryAccumulator {
        BigDecimal depositsAllTime      = BigDecimal.ZERO;
        BigDecimal depositsThisMonth    = BigDecimal.ZERO;
        BigDecimal depositsToday        = BigDecimal.ZERO;
        BigDecimal withdrawalsAllTime   = BigDecimal.ZERO;
        BigDecimal withdrawalsThisMonth = BigDecimal.ZERO;
        long depositCount    = 0;
        long withdrawalCount = 0;

        void addDeposit(BigDecimal amount, Instant createdAt, Instant startOfMonth, Instant startOfToday) {
            BigDecimal amt = amount != null ? amount : BigDecimal.ZERO;
            depositsAllTime = depositsAllTime.add(amt);
            depositCount++;
            if (createdAt != null && !createdAt.isBefore(startOfMonth)) {
                depositsThisMonth = depositsThisMonth.add(amt);
            }
            if (createdAt != null && !createdAt.isBefore(startOfToday)) {
                depositsToday = depositsToday.add(amt);
            }
        }

        void addWithdrawal(BigDecimal amount, Instant createdAt, Instant startOfMonth) {
            BigDecimal amt = amount != null ? amount : BigDecimal.ZERO;
            withdrawalsAllTime = withdrawalsAllTime.add(amt);
            withdrawalCount++;
            if (createdAt != null && !createdAt.isBefore(startOfMonth)) {
                withdrawalsThisMonth = withdrawalsThisMonth.add(amt);
            }
        }
    }

    // ─── All Users (paginated + search) ──────────────────────────────────────

    /**
     * Uses JPA Specifications so null params are simply omitted from the WHERE
     * clause — avoids the PostgreSQL enum-cast error that plagued the old
     * @Query("... :role IS NULL OR u.role = :role ...") approach with Hibernate.
     */
    public Page<SuperAdminDtos.UserSummaryDto> listUsers(
            String search, UserRole role, Pageable pageable) {
        log.info("listUsers: search='{}' role={}", search, role);

        Specification<User> spec = Specification.where(null);

        if (role != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), role));
        }

        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")),     pattern),
                    cb.like(cb.lower(root.get("firstName")), pattern),
                    cb.like(cb.lower(root.get("lastName")),  pattern)
            ));
        }

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return userRepo.findAll(spec, p).map(this::toUserSummary);
    }

    /**
     * Scoped twin of {@link #listUsers(String, UserRole, Pageable)}: only users
     * whose id is in {@code allowedUserIds} are returned. A super admin passes
     * their visible set (subtree users + own admins); the site owner uses the
     * unscoped original. An empty set matches nothing, never everything.
     */
    public Page<SuperAdminDtos.UserSummaryDto> listUsers(
            String search, UserRole role, Pageable pageable, Set<UUID> allowedUserIds) {
        log.info("listUsers scoped: search='{}' role={} allowed={}", search, role,
                allowedUserIds == null ? null : allowedUserIds.size());

        Specification<User> spec = Specification.where(null);

        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> cb.disjunction());
        } else {
            spec = spec.and((root, q, cb) -> root.get("id").in(allowedUserIds));
        }

        if (role != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), role));
        }

        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")),     pattern),
                    cb.like(cb.lower(root.get("firstName")), pattern),
                    cb.like(cb.lower(root.get("lastName")),  pattern)
            ));
        }

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return userRepo.findAll(spec, p).map(this::toUserSummary);
    }

    // ─── Single User Detail ───────────────────────────────────────────────────

    public SuperAdminDtos.UserDetailDto getUserDetail(UUID userId) {
        log.info("getUserDetail: userId='{}'", userId);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        Wallet wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        long txCount                 = txRepo.countByWalletId(wallet.getId());
        BigDecimal walletDeposits    = txRepo.sumByKindSince(wallet.getId(), TxKind.DEPOSIT,  Instant.EPOCH);
        BigDecimal walletWithdrawals = txRepo.sumByKindSince(wallet.getId(), TxKind.WITHDRAW, Instant.EPOCH);

        var walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                txCount, walletDeposits, walletWithdrawals);

        Instant createdAt = user.getCreatedAt() != null
                ? user.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.UserDetailDto(
                user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getCountry(),
                user.getRole().name(), user.getStatus().name(),
                user.isEmailVerified(), createdAt, walletDto);
    }

    // ─── User Deposit History ─────────────────────────────────────────────────

    /**
     * Paginated DEPOSIT transactions for one user, newest first. Each row
     * carries the user's country and the wallet currency so the client can
     * render ₵ or ₦ without a second lookup.
     */
    public Page<SuperAdminDtos.UserDepositDto> listUserDeposits(UUID userId, Pageable pageable) {
        log.info("listUserDeposits: userId='{}'", userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        Wallet wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        String country = CountryUtils.normalize(user.getCountry());
        String currency = (wallet.getCurrency() != null && !wallet.getCurrency().isBlank())
                ? wallet.getCurrency() : CountryUtils.currencyOf(country);

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return txRepo
                .findByWalletIdAndKindOrderByCreatedAtDesc(wallet.getId(), TxKind.DEPOSIT, p)
                .map(tx -> new SuperAdminDtos.UserDepositDto(
                        tx.getId(), tx.getWalletId(),
                        user.getId(), user.getEmail(),
                        user.getFirstName(), user.getLastName(),
                        country, currency,
                        tx.getAmount(), tx.getBalanceAfter(),
                        tx.getProviderRef(), tx.getStatus(), tx.getCreatedAt()));
    }

    // ─── Single Admin Detail ──────────────────────────────────────────────────

    public SuperAdminDtos.AdminDetailDto getAdminDetail(UUID adminId) {
        log.info("getAdminDetail: adminId='{}'", adminId);

        User admin = userRepo.findById(adminId)
                .orElseThrow(() -> ApiException.notFound("Admin not found"));

        if (admin.getRole() != UserRole.ADMIN) {
            throw ApiException.badRequest("User is not an ADMIN");
        }

        Wallet wallet = walletRepo.findByUserId(adminId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        long txCount         = txRepo.countByWalletId(wallet.getId());
        BigDecimal deposited = txRepo.sumByKindSince(wallet.getId(), TxKind.DEPOSIT,  Instant.EPOCH);
        BigDecimal withdrawn = txRepo.sumByKindSince(wallet.getId(), TxKind.WITHDRAW, Instant.EPOCH);

        var walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                txCount, deposited, withdrawn);

        List<ReferralLink> links = referralLinkRepo.findByAdminId(adminId);
        var referralDto = links.stream()
                .filter(ReferralLink::isActive)
                .findFirst()
                .or(() -> links.stream().findFirst())
                .map(link -> new SuperAdminDtos.ReferralSummaryDto(
                        link.getId(), link.getCode(), link.getCommissionPercent(), null, null))
                .orElse(null);

        Instant adminCreatedAt = admin.getCreatedAt() != null
                ? admin.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.AdminDetailDto(
                admin.getId(), admin.getEmail(), admin.getFirstName(), admin.getLastName(),
                admin.getPhone(), admin.getCountry(), admin.isEmailVerified(),
                adminCreatedAt, walletDto, referralDto);
    }

    // ─── Platform-wide Transactions (paginated + filtered) ───────────────────

    /**
     * Wallet and user lookups are batched per page rather than performed inside
     * the map. The previous version issued two extra queries per row, which at
     * size=500 during CSV export meant roughly a thousand round-trips per page.
     */
    public Page<SuperAdminDtos.TransactionDto> listTransactions(
            TxKind kind, String status, UUID walletId,
            Instant from, Instant to, Pageable pageable) {
        log.info("listTransactions: kind={} status={} walletId={}", kind, status, walletId);

        Pageable p = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Transaction> page =
                txRepo.findAll(TransactionSpecs.filtered(kind, status, walletId, from, to), p);

        List<Transaction> rows = page.getContent();
        if (rows.isEmpty()) return page.map(tx -> toTransactionDto(tx, null, null, null));

        Set<UUID> walletIds = rows.stream()
                .map(Transaction::getWalletId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UUID> walletToUser = walletRepo.findAllById(walletIds).stream()
                .filter(w -> w != null && w.getId() != null && w.getUserId() != null)
                .collect(Collectors.toMap(Wallet::getId, Wallet::getUserId, (a, b) -> a));

        Set<UUID> userIds = new HashSet<>(walletToUser.values());
        Map<UUID, User> usersById = userIds.isEmpty() ? Map.of()
                : userRepo.findAllById(userIds).stream()
                .filter(u -> u != null && u.getId() != null)
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return page.map(tx -> {
            UUID userId = walletToUser.get(tx.getWalletId());
            User u = userId != null ? usersById.get(userId) : null;
            return toTransactionDto(tx, userId,
                    u != null ? u.getEmail() : null,
                    u != null ? CountryUtils.normalize(u.getCountry()) : CountryUtils.UNKNOWN);
        });
    }

    /**
     * Scoped twin of {@link #listTransactions(TxKind, String, UUID, Instant, Instant, Pageable)}:
     * only transactions whose wallet is in {@code allowedWalletIds} are returned.
     */
    public Page<SuperAdminDtos.TransactionDto> listTransactions(
            TxKind kind, String status, UUID walletId,
            Instant from, Instant to, Pageable pageable, Set<UUID> allowedWalletIds) {
        log.info("listTransactions scoped: kind={} status={} walletId={} wallets={}",
                kind, status, walletId, allowedWalletIds == null ? null : allowedWalletIds.size());

        Pageable p = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Transaction> spec = TransactionSpecs.filtered(kind, status, walletId, from, to)
                .and(TransactionSpecs.walletIdIn(allowedWalletIds));

        Page<Transaction> page = txRepo.findAll(spec, p);

        List<Transaction> rows = page.getContent();
        if (rows.isEmpty()) return page.map(tx -> toTransactionDto(tx, null, null, null));

        Set<UUID> walletIds = rows.stream()
                .map(Transaction::getWalletId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UUID> walletToUser = walletRepo.findAllById(walletIds).stream()
                .filter(w -> w != null && w.getId() != null && w.getUserId() != null)
                .collect(Collectors.toMap(Wallet::getId, Wallet::getUserId, (a, b) -> a));

        Set<UUID> userIds = new HashSet<>(walletToUser.values());
        Map<UUID, User> usersById = userIds.isEmpty() ? Map.of()
                : userRepo.findAllById(userIds).stream()
                .filter(u -> u != null && u.getId() != null)
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return page.map(tx -> {
            UUID userId = walletToUser.get(tx.getWalletId());
            User u = userId != null ? usersById.get(userId) : null;
            return toTransactionDto(tx, userId,
                    u != null ? u.getEmail() : null,
                    u != null ? CountryUtils.normalize(u.getCountry()) : CountryUtils.UNKNOWN);
        });
    }

    // ─── Affiliate Withdrawal History (all statuses) ──────────────────────────

    public Page<com.speedbet.api.affiliate.AffiliateWithdrawalRequest> listWithdrawals(
            AffiliateWithdrawalStatus status, Pageable pageable) {
        log.info("listWithdrawals: status={}", status);

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));

        if (status != null) {
            return withdrawalRepo.findByStatusOrderByRequestedAtDesc(status, p);
        }
        return withdrawalRepo.findAll(p);
    }

    /**
     * Scoped twin of {@link #listWithdrawals(AffiliateWithdrawalStatus, Pageable)}:
     * only withdrawal requests from users in {@code allowedUserIds} are returned.
     */
    public Page<com.speedbet.api.affiliate.AffiliateWithdrawalRequest> listWithdrawals(
            AffiliateWithdrawalStatus status, Pageable pageable, Set<UUID> allowedUserIds) {
        log.info("listWithdrawals scoped: status={} allowed={}",
                status, allowedUserIds == null ? null : allowedUserIds.size());

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));

        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            return Page.empty(p);
        }
        if (status != null) {
            return withdrawalRepo.findByStatusAndUserIdInOrderByRequestedAtDesc(status, allowedUserIds, p);
        }
        return withdrawalRepo.findByUserIdInOrderByRequestedAtDesc(allowedUserIds, p);
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private SuperAdminDtos.UserSummaryDto toUserSummary(User u) {
        return new SuperAdminDtos.UserSummaryDto(
                u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getPhone(), u.getCountry(), u.getRole().name(), u.getStatus().name(),
                u.isEmailVerified(),
                u.getCreatedAt() != null ? u.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null);
    }

    private SuperAdminDtos.TransactionDto toTransactionDto(
            Transaction tx, UUID userId, String email, String country) {
        return new SuperAdminDtos.TransactionDto(
                tx.getId(), tx.getWalletId(), userId, email, country,
                tx.getKind(), tx.getAmount(), tx.getBalanceAfter(),
                tx.getProviderRef(), tx.getStatus(), tx.getMetadata(),
                tx.getCreatedAt());
    }
}