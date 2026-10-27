package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RoyalBet KYC business logic — a self-contained sub-service.
 *
 * Isolation rules (this backend is shared by several frontends, each with
 * its own rules): this service owns ONLY the kyc_submissions table. It
 * never writes to users, wallets, transactions or any other module's
 * data, and it never calls the wallet/deposit services. The only shared
 * input is the authenticated principal produced by the existing auth
 * service — the submission's status in this module is the single source
 * of truth for "is this user KYC-verified".
 *
 * One live submission per user: re-submitting while PENDING refreshes the
 * pending record in place; re-submitting after a REJECTED creates a fresh
 * row (the rejected history stays on file); submitting while APPROVED is
 * a conflict.
 *
 * 7UP extension: submit() now also stores country (falling back to the
 * account country when the request omits it), date of birth (18+ enforced
 * when present), and the photo link sent as photoUrl (preferred) or
 * imageUrl (legacy). Country/dob are deliberately NOT @NotBlank/@NotNull
 * in the DTO: older clients that omit them must keep working on this
 * shared backend; the 7UP frontend always sends them.
 */
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycSubmissionRepository submissions;
    private final KycSettingsService settings;

    /**
     * Master switch for server-side KYC enforcement on the money paths.
     * This backend is shared by several frontends, but KYC is a RoyalBet
     * module — enforcement must stay OFF everywhere else, or players on
     * the other frontends (who can never submit KYC) would be blocked
     * from withdrawing. Enable ONLY on the RoyalBet deployment by
     * setting the KYC_ENFORCE_WITHDRAWALS=true environment variable.
     */
    @Value("${KYC_ENFORCE_WITHDRAWALS:false}")
    private boolean enforceWithdrawals;

    /** Whether the money paths must enforce KYC on this deployment. */
    public boolean isEnforcementEnabled() {
        return enforceWithdrawals;
    }

    @Transactional
    public KycSubmission submit(User user, KycDtos.SubmitKycRequest req) {
        // Super-admin switch (default CLOSED): no new submissions while off.
        if (!settings.isSubmissionsOpen()) {
            throw ApiException.forbidden("KYC submissions are currently closed.");
        }
        var latest = submissions.findFirstByUserIdOrderBySubmittedAtDesc(user.getId());
        if (latest.isPresent() && latest.get().getStatus() == KycStatus.APPROVED) {
            throw ApiException.conflict("Your identity is already verified.");
        }

        // 7UP: date of birth must be 18+ whenever the client sends one.
        if (req.getDob() != null && req.getDob().isAfter(LocalDate.now().minusYears(18))) {
            throw ApiException.badRequest("You must be 18 or older.");
        }

        String displayName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        if (displayName.isEmpty()) displayName = user.getEmail();

        KycSubmission target;
        if (latest.isPresent() && latest.get().getStatus() == KycStatus.PENDING) {
            target = latest.get();
        } else {
            target = KycSubmission.builder().userId(user.getId()).build();
        }
        target.setEmail(user.getEmail());
        target.setUserName(displayName);
        target.setCountry(req.getCountry() != null && !req.getCountry().isBlank()
                ? req.getCountry().trim() : user.getCountry());
        target.setFullName(req.getFullName().trim());
        target.setIdType(req.getIdType().trim());
        target.setIdNumber(req.getIdNumber().trim());
        target.setDob(req.getDob());
        String image = req.getPhotoUrl() != null && !req.getPhotoUrl().isBlank()
                ? req.getPhotoUrl().trim() : req.getImageUrl();
        target.setImageUrl(image);
        // Auto-approve (7UP, Oct 2026): a complete submission verifies the user
        // immediately so withdrawals unlock without waiting for an admin. The
        // autoApproved flag marks these for later human review; admins can still
        // suspend the account for a fake ID (deactivate blocks login).
        target.setStatus(KycStatus.APPROVED);
        target.setAutoApproved(true);
        target.setReviewNote(null);
        target.setReviewedBy(null);
        target.setReviewedAt(Instant.now());
        target.setSubmittedAt(Instant.now());
        return submissions.save(target);
    }

    /**
     * True only when the user's latest submission is APPROVED.
     * This is the check the money paths use to enforce KYC — the
     * submission's status in this module is the single source of truth.
     */
    @Transactional(readOnly = true)
    public boolean isVerified(UUID userId) {
        return submissions.findFirstByUserIdOrderBySubmittedAtDesc(userId)
                .map(s -> s.getStatus() == KycStatus.APPROVED)
                .orElse(false);
    }

    /** The user's latest submission, or null if they never submitted. */
    @Transactional(readOnly = true)
    public KycSubmission mine(User user) {
        return submissions.findFirstByUserIdOrderBySubmittedAtDesc(user.getId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<KycSubmission> list(String status) {
        if (status != null && !status.isBlank()) {
            KycStatus parsed;
            try {
                parsed = KycStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown KYC status: " + status);
            }
            return submissions.findByStatusOrderBySubmittedAtDesc(parsed);
        }
        return submissions.findAllByOrderBySubmittedAtDesc();
    }

    @Transactional
    public KycSubmission review(User admin, UUID submissionId, KycDtos.ReviewKycRequest req) {
        KycStatus decision;
        try {
            decision = KycStatus.valueOf(req.getDecision().trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("decision must be APPROVED or REJECTED");
        }
        if (decision == KycStatus.PENDING) {
            throw ApiException.badRequest("decision must be APPROVED or REJECTED");
        }
        String note = req.getNote() == null ? "" : req.getNote().trim();
        if (decision == KycStatus.REJECTED && note.isEmpty()) {
            throw ApiException.badRequest("A rejection reason is required.");
        }

        KycSubmission sub = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("KYC submission not found: " + submissionId));
        sub.setStatus(decision);
        sub.setReviewNote(decision == KycStatus.REJECTED ? note : null);
        sub.setReviewedBy(admin.getId());
        sub.setReviewedAt(Instant.now());
        return submissions.save(sub);
    }
}
