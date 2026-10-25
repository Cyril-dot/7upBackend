package com.speedbet.api.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BranchGatewaySettingRepository extends JpaRepository<BranchGatewaySetting, UUID> {
    Optional<BranchGatewaySetting> findBySuperAdminId(UUID superAdminId);
}
