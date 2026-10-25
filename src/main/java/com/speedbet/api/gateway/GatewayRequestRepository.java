package com.speedbet.api.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayRequestRepository extends JpaRepository<GatewayRequest, UUID> {
    /** The single pending request for a super admin, if any. */
    Optional<GatewayRequest> findBySuperAdminIdAndStatus(UUID superAdminId, String status);
    /** All requests for a super admin in a given status (used to supersede pending ones). */
    List<GatewayRequest> findAllBySuperAdminIdAndStatus(UUID superAdminId, String status);
    List<GatewayRequest> findByStatusOrderByRequestedAtAsc(String status);
}
