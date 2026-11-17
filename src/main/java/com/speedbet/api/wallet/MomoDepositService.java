package com.speedbet.api.wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.gateway.GatewayService;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.UserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MomoDepositService {

    private final MomoDepositRepository repo;
    private final WalletService         walletService;
    private final ReferralService       referralService;
    private final UserRepository        userRepository;
    private final GatewayService        gatewayService;

    /** HolyStar's floor for a manual MoMo deposit (GH₵100). The branch's own
     *  Ghana minimum still applies on top — a branch with a higher floor
     *  never lets a manual MoMo deposit slip under it. */
    private static final BigDecimal MOMO_MIN_AMOUNT = new BigDecimal("100.00");

    // ── User: own history ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MomoDeposit> getMyDeposits(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Admin: queries ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MomoDeposit> getAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<MomoDeposit> getPending(Pageable pageable) {
        return repo.findByStatusOrderByCreatedAtAsc(MomoDepositStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public String getUserEmail(UUID userId) {
        return userRepository.findById(userId).map(user -> user.getEmail()).orElse(null);
    }

    @Transactional(readOnly = true)
    public MomoDeposit getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Manual MoMo deposit not found: " + id));
    }

    // ── User: submit a deposit ─────────────────────────────────────────────────

    @Transactional
    public MomoDeposit submit(UUID userId, MomoDepositDtos.SubmitRequest req) {
        var user = userRepository.findById(userId).orElse(null);
        // Deposits maintenance (v161): a paused branch refuses new manual
        // MoMo submissions server-side too — unlike the older bank-deposit
        // submit, which the page alone blocks.
        gatewayService.requireDepositsEnabled(user);
        // Prevent duplicate submissions with the same MoMo transaction ID
        if (repo.existsByTransactionId(req.getTransactionId())) {
            throw ApiException.conflict(
                    "A deposit with this transaction ID was already submitted.");
        }
        // Ghana minimum: the branch super admin's setting, else the platform
        // GH₵50 — never below the HolyStar-style GH₵100 floor. Enforced here
        // as well as on the page, so a direct API submit cannot slip under it.
        var ghsSent = req.getGhsAmountSent();
        if (ghsSent != null) {
            var effGhMin = MOMO_MIN_AMOUNT;
            if (user != null) {
                var v = gatewayService.branchLimitsFor(user).get("ghMinDeposit");
                if (v instanceof BigDecimal bd && bd.compareTo(effGhMin) > 0) effGhMin = bd;
            }
            if (ghsSent.compareTo(effGhMin) < 0) {
                throw ApiException.badRequest("Minimum manual MoMo deposit is GH₵"
                        + java.text.NumberFormat.getInstance(java.util.Locale.US).format(effGhMin));
            }
        }
        MomoDeposit deposit = MomoDeposit.builder()
                .userId(userId)
                .transactionId(req.getTransactionId().trim())
                .ghsAmountSent(req.getGhsAmountSent())
                .expectedGhsCredit(req.getExpectedGhsCredit())
                .senderName(req.getSenderName())
                .screenshotUrl(req.getScreenshotUrl())
                .userNote(req.getUserNote())
                .status(MomoDepositStatus.PENDING)
                .build();
        return repo.save(deposit);
    }

    // ── Admin: approve ────────────────────────────────────────────────────────

    @Transactional
    public MomoDeposit approve(UUID depositId, UUID adminId, MomoDepositDtos.ApproveRequest req) {

        MomoDeposit deposit = getById(depositId);

        if (deposit.getStatus() != MomoDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be approved. Current status: " + deposit.getStatus());
        }

        // Credit the wallet
        Transaction tx = walletService.credit(
                deposit.getUserId(),
                req.getCreditedGhsAmount(),
                TxKind.DEPOSIT,
                "MOMO_DEPOSIT:" + deposit.getTransactionId(),
                Map.of(
                        "depositId",         deposit.getId().toString(),
                        "transactionId",     deposit.getTransactionId(),
                        "ghsAmountSent",     deposit.getGhsAmountSent().toPlainString(),
                        "approvedBy",        adminId.toString()
                )
        );

        // Credit referral commission if this user was referred
        referralService.attributeCommission(deposit.getUserId(), req.getCreditedGhsAmount());

        deposit.setStatus(MomoDepositStatus.APPROVED);
        deposit.setCreditedGhsAmount(req.getCreditedGhsAmount());
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());
        deposit.setWalletTransactionId(tx.getId());

        return repo.save(deposit);
    }

    // ── Admin: reject ─────────────────────────────────────────────────────────

    @Transactional
    public MomoDeposit reject(UUID depositId, UUID adminId, MomoDepositDtos.RejectRequest req) {

        MomoDeposit deposit = getById(depositId);

        if (deposit.getStatus() != MomoDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be rejected. Current status: " + deposit.getStatus());
        }

        deposit.setStatus(MomoDepositStatus.REJECTED);
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());

        return repo.save(deposit);
    }
}
