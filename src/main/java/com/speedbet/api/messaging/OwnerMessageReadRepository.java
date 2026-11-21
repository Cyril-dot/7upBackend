package com.speedbet.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerMessageReadRepository extends JpaRepository<OwnerMessageRead, UUID> {

    long countByMessageId(UUID messageId);

    boolean existsByMessageIdAndSuperAdminId(UUID messageId, UUID superAdminId);

    Optional<OwnerMessageRead> findByMessageIdAndSuperAdminId(UUID messageId, UUID superAdminId);

    /** All read receipts for one message, oldest read first (owner detail view). */
    List<OwnerMessageRead> findByMessageIdOrderByReadAtAsc(UUID messageId);
}
