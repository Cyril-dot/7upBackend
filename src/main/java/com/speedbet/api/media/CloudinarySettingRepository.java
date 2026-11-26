package com.speedbet.api.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CloudinarySettingRepository extends JpaRepository<CloudinarySetting, UUID> {
}
