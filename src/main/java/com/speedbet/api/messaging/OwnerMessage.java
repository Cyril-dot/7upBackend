package com.speedbet.api.messaging;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One broadcast message from the site owner to ALL super admins.
 * The message itself carries no per-recipient state — reads are tracked
 * separately in OwnerMessageRead, replies in OwnerMessageReply.
 */
@Entity
@Table(name = "owner_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The site owner who sent the broadcast. */
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    /** Text body (TEXT, IMAGE, FILE and VOICE messages may carry a caption). */
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

    /** The shared link, for LINK messages (must start with http:// or https://). */
    @Column(name = "link_url", columnDefinition = "TEXT")
    private String linkUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
