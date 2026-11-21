package com.speedbet.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OwnerMessageReplyRepository extends JpaRepository<OwnerMessageReply, UUID> {

    List<OwnerMessageReply> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    long countByMessageId(UUID messageId);
}
