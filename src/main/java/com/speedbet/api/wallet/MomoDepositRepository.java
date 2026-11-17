package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MomoDepositRepository extends JpaRepository<MomoDeposit, UUID> {

    /** All deposits for a specific user, newest first */
    Page<MomoDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** All pending deposits, oldest first (FIFO review queue) */
    Page<MomoDeposit> findByStatusOrderByCreatedAtAsc(MomoDepositStatus status, Pageable pageable);

    /** Duplicate transaction-ID guard */
    boolean existsByTransactionId(String transactionId);
}
