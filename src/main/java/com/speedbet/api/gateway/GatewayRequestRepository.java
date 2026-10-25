package com.speedbet.api.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayRequestRepository extends JpaRepository<GatewayRequest, UUID> {
    Optional<GatewayRequest> findBySuperAdminIdAndStatus(UUID superAdminId, String status);
    List<GatewayRequest> findBySuperAdminIdAndStatus(UUID superAdminId, String status);
    List<GatewayRequest> findByStatusOrderByRequestedAtAsc(String status);
}
