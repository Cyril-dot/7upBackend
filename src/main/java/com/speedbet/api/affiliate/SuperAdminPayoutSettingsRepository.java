package com.speedbet.api.affiliate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SuperAdminPayoutSettingsRepository extends JpaRepository<SuperAdminPayoutSettings, Long> {
}
