package com.speedbet.api.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateWithdrawalRepository extends JpaRepository<AffiliateWithdrawalRequest, UUID> {

    Page<AffiliateWithdrawalRequest> findByUserIdOrderByRequestedAtDesc(UUID userId, Pageable pageable);

    List<AffiliateWithdrawalRequest> findByStatus(AffiliateWithdrawalStatus status);

    Page<AffiliateWithdrawalRequest> findByStatusAndUserIdInOrderByRequestedAtDesc(
            AffiliateWithdrawalStatus status, java.util.Collection<java.util.UUID> userIds, Pageable pageable);

    Page<AffiliateWithdrawalRequest> findByUserIdInOrderByRequestedAtDesc(
            java.util.Collection<java.util.UUID> userIds, Pageable pageable);

    Page<AffiliateWithdrawalRequest> findByStatusOrderByRequestedAtDesc(
            AffiliateWithdrawalStatus status, Pageable pageable);

    Optional<AffiliateWithdrawalRequest> findByReference(String reference);
}