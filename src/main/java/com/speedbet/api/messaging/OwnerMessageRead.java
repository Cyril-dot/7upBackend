package com.speedbet.api.messaging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One super admin's read receipt for one owner broadcast message.
 * One row per (message, super admin) — enforced by the unique constraint.
 */
@Entity
@Table(
        name = "owner_message_reads",
        uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "super_admin_id"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerMessageRead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "super_admin_id", nullable = false)
    private UUID superAdminId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    @PrePersist
    protected void onCreate() {
        if (readAt == null) readAt = Instant.now();
    }
}
