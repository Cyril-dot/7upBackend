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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankDepositService {

    private final BankDepositRepository repo;
    private final WalletService         walletService;
    private final ReferralService       referralService;
    private final UserRepository        userRepository;
    private final GatewayService        gatewayService;
    private static final java.math.BigDecimal MOMO_MAX_AMOUNT = new java.math.BigDecimal("30000.00");

    // ── User: own history ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BankDeposit> getMyDeposits(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Admin: queries ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BankDeposit> getAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<BankDeposit> getPending(Pageable pageable) {
        return repo.findByStatusOrderByCreatedAtAsc(BankDepositStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public Page<BankDeposit> getMomo(Pageable pageable) {
        return repo.findByNgnAmountSentLessThanOrderByCreatedAtDesc(MOMO_MAX_AMOUNT, pageable);
    }

    @Transactional(readOnly = true)
    public Page<BankDeposit> getPendingMomo(Pageable pageable) {
        return repo.findByStatusAndNgnAmountSentLessThanOrderByCreatedAtAsc(BankDepositStatus.PENDING, MOMO_MAX_AMOUNT, pageable);
    }

    @Transactional(readOnly = true)
    public String getUserEmail(UUID userId) {
        return userRepository.findById(userId).map(user -> user.getEmail()).orElse(null);
    }

    @Transactional(readOnly = true)
    public BankDeposit getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank deposit not found: " + id));
    }

    // ── User: submit a deposit ─────────────────────────────────────────────────

    @Transactional
    public BankDeposit submit(UUID userId, BankDepositDtos.SubmitRequest req) {
        // Prevent duplicate submissions with the same transfer reference
        if (repo.existsByTransferReference(req.getTransferReference())) {
            throw ApiException.conflict(
                    "A deposit with this transfer reference was already submitted.");
        }
        // Branch Nigeria minimum: the branch super admin's setting, else
        // the platform ₦10,000. Enforced here as well as on the page, so
        // a direct API submit cannot slip under the branch's floor.
        var ngnSent = req.getNgnAmountSent();
        if (ngnSent != null) {
            var effNgMin = new java.math.BigDecimal("10000");
            var u = userRepository.findById(userId).orElse(null);
            if (u != null) {
                var v = gatewayService.branchLimitsFor(u).get("ngMinDeposit");
                if (v instanceof java.math.BigDecimal bd) effNgMin = bd;
            }
            if (ngnSent.compareTo(effNgMin) < 0) {
                throw ApiException.badRequest("Minimum manual deposit is ₦"
                        + java.text.NumberFormat.getInstance(java.util.Locale.US).format(effNgMin));
            }
        }
        BankDeposit deposit = BankDeposit.builder()
                .userId(userId)
                .transferReference(req.getTransferReference().trim())
                .ngnAmountSent(req.getNgnAmountSent())
                .expectedNgnCredit(req.getExpectedNgnCredit())
                .senderAccountName(req.getSenderAccountName())
                .screenshotUrl(req.getScreenshotUrl())
                .userNote(req.getUserNote())
                .status(BankDepositStatus.PENDING)
                .build();
        return repo.save(deposit);
    }

    // ── Admin: approve ────────────────────────────────────────────────────────

    @Transactional
    public BankDeposit approve(UUID depositId, UUID adminId, BankDepositDtos.ApproveRequest req) {

        BankDeposit deposit = getById(depositId);

        if (deposit.getStatus() != BankDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be approved. Current status: " + deposit.getStatus());
        }

        // Credit the wallet
        Transaction tx = walletService.credit(
                deposit.getUserId(),
                req.getCreditedNgnAmount(),
                TxKind.DEPOSIT,
                "BANK_DEPOSIT:" + deposit.getTransferReference(),
                Map.of(
                        "depositId",         deposit.getId().toString(),
                        "transferReference", deposit.getTransferReference(),
                        "ngnAmountSent",     deposit.getNgnAmountSent().toPlainString(),
                        "approvedBy",        adminId.toString()
                )
        );

        // Credit referral commission if this user was referred
        referralService.attributeCommission(deposit.getUserId(), req.getCreditedNgnAmount());

        deposit.setStatus(BankDepositStatus.APPROVED);
        deposit.setCreditedNgnAmount(req.getCreditedNgnAmount());
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());
        deposit.setWalletTransactionId(tx.getId());

        return repo.save(deposit);
    }

    // ── Admin: reject ─────────────────────────────────────────────────────────

    @Transactional
    public BankDeposit reject(UUID depositId, UUID adminId, BankDepositDtos.RejectRequest req) {

        BankDeposit deposit = getById(depositId);

        if (deposit.getStatus() != BankDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be rejected. Current status: " + deposit.getStatus());
        }

        deposit.setStatus(BankDepositStatus.REJECTED);
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());

        return repo.save(deposit);
    }
}
