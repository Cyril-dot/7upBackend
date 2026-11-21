package com.speedbet.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OwnerMessageRepository extends JpaRepository<OwnerMessage, UUID> {

    List<OwnerMessage> findAllByOrderByCreatedAtDesc();
}
