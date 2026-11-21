package com.speedbet.api.messaging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One super admin's reply to an owner broadcast message.
 * Replies are visible to the site owner (in the message detail) — they are
 * not broadcast to the other super admins.
 */
@Entity
@Table(name = "owner_message_replies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerMessageReply {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    /** The super admin who replied. */
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    /** Text body (TEXT, IMAGE, FILE and VOICE replies may carry a caption). */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "msg_type", nullable = false, length = 20)
    private OwnerMessageType msgType;

    /** Data-URL / stored URL of the image, file or voice note (max ~3MB). */
    @Column(name = "attachment_url", columnDefinition = "TEXT")
    private String attachmentUrl;

    /** Original file name, for FILE (and IMAGE) attachments. */
    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    /** The shared link, for LINK replies (must start with http:// or https://). */
    @Column(name = "link_url", columnDefinition = "TEXT")
    private String linkUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
