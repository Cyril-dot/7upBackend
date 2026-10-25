package com.speedbet.api.affiliate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SuperAdminPayoutRequestRepository extends JpaRepository<SuperAdminPayoutRequest, UUID> {

    /** One open request per super admin: a second request 400s until the first resolves. */
    boolean existsBySuperAdminIdAndStatusIn(UUID superAdminId, List<PayoutStatus> statuses);

    List<SuperAdminPayoutRequest> findBySuperAdminIdOrderByCreatedAtDesc(UUID superAdminId);

    List<SuperAdminPayoutRequest> findByStatusOrderByCreatedAtDesc(PayoutStatus status);

    List<SuperAdminPayoutRequest> findAllByOrderByCreatedAtDesc();

    long countByStatus(PayoutStatus status);
}
