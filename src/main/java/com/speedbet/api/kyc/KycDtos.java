package com.speedbet.api.kyc;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class KycDtos {

    @Data
    public static class SubmitKycRequest {
        @NotBlank(message = "fullName is required")
        private String fullName;

        @NotBlank(message = "idType is required")
        private String idType;

        @NotBlank(message = "idNumber is required")
        private String idNumber;

        /**
         * Submitter's country (e.g. Ghana / Nigeria). The 7UP frontend
         * always sends it; when blank the service falls back to the
         * account country, so older clients that omit it keep working.
         */
        private String country;

        /**
         * Date of birth. When present it must be 18+ (enforced in
         * KycService); left nullable so older clients that omit it keep
         * working. The 7UP frontend always sends it.
         */
        private LocalDate dob;

        /** ImgBB-hosted ID image URL (hosted by the frontend before submit). */
        private String imageUrl;

        /**
         * Alias of imageUrl — the 7UP frontend sends photoUrl. The service
         * prefers photoUrl when present, else imageUrl.
         */
        private String photoUrl;
    }

    @Data
    public static class ReviewKycRequest {
        /** APPROVED or REJECTED. */
        @NotBlank(message = "decision is required")
        private String decision;

        /** Required when rejecting — the user sees it. */
        private String note;
    }

    @Data
    public static class KycSubmissionResponse {
        private UUID id;
        private UUID userId;
        private String email;
        private String userName;
        private String country;
        private String fullName;
        private String idType;
        private String idNumber;
        private LocalDate dob;
        private String imageUrl;
        /** Alias of imageUrl, for the 7UP frontend. */
        private String photoUrl;
        private String status;
        private String reviewNote;
        private UUID reviewedBy;
        private Instant submittedAt;
        private Instant reviewedAt;
        private boolean autoApproved;

        public static KycSubmissionResponse from(KycSubmission s) {
            KycSubmissionResponse r = new KycSubmissionResponse();
            r.id = s.getId();
            r.userId = s.getUserId();
            r.email = s.getEmail();
            r.userName = s.getUserName();
            r.country = s.getCountry();
            r.fullName = s.getFullName();
            r.idType = s.getIdType();
            r.idNumber = s.getIdNumber();
            r.dob = s.getDob();
            r.imageUrl = s.getImageUrl();
            r.photoUrl = s.getImageUrl();
            r.status = s.getStatus() == null ? null : s.getStatus().name();
            r.reviewNote = s.getReviewNote();
            r.reviewedBy = s.getReviewedBy();
            r.submittedAt = s.getSubmittedAt();
            r.reviewedAt = s.getReviewedAt();
            r.autoApproved = s.isAutoApproved();
            return r;
        }
    }
}
