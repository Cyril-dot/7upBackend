package com.speedbet.api.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class OwnerMessageDtos {

    // ─── Requests ─────────────────────────────────────────────────────────────

    /**
     * Sent by the site owner (broadcast) and by a super admin (reply).
     * msgType is a String here — the service parses it to OwnerMessageType
     * (invalid value → 400 "Invalid message type.").
     * At least one of body / attachmentUrl / linkUrl must be non-blank.
     */
    public record SendMessageRequest(
            String body,
            String msgType,
            String attachmentUrl,
            String attachmentName,
            String linkUrl
    ) {}

    // ─── Message DTO ──────────────────────────────────────────────────────────

    /**
     * unread is null on the owner endpoints; true/false on the super-admin
     * list/detail (whether THIS super admin has read the message).
     */
    public record MessageDto(
            UUID id,
            UUID senderId,
            String senderName,
            String body,
            String msgType,
            String attachmentUrl,
            String attachmentName,
            String linkUrl,
            Instant createdAt,
            long readCount,
            long totalSuperAdmins,
            long replyCount,
            Boolean unread
    ) {}

    // ─── Reply DTO ────────────────────────────────────────────────────────────

    public record ReplyDto(
            UUID id,
            UUID messageId,
            UUID senderId,
            String senderName,
            String senderEmail,
            String body,
            String msgType,
            String attachmentUrl,
            String attachmentName,
            String linkUrl,
            Instant createdAt
    ) {}

    // ─── Read receipt DTO ─────────────────────────────────────────────────────

    public record ReadByDto(
            UUID superAdminId,
            String superAdminName,
            String superAdminEmail,
            Instant readAt
    ) {}

    // ─── Detail DTO ───────────────────────────────────────────────────────────

    /**
     * readBy is null on the super-admin endpoint; populated (oldest read
     * first) on the owner endpoint.
     */
    public record MessageDetailDto(
            MessageDto message,
            List<ReplyDto> replies,
            List<ReadByDto> readBy
    ) {}

    // ─── Unread count DTO ─────────────────────────────────────────────────────

    public record UnreadCountDto(
            long unreadCount
    ) {}
}
