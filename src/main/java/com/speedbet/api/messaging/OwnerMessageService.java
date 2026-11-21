package com.speedbet.api.messaging;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.messaging.OwnerMessageDtos.*;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Owner → super-admin messaging.
 *
 * The site owner broadcasts one message to ALL super admins; each super
 * admin reads it in their inbox and may reply to the owner. Delivery is
 * instant while the super admin is on the site: the frontend polls the
 * unread-count endpoint every few seconds and shows a badge + popup the
 * moment a new message lands (the codebase has no live WebSocket client,
 * so polling is the delivery path — same as the deposit poll and the
 * v139 notification centre).
 *
 * Reads are tracked per (message, super admin) so the owner can see who
 * has read each broadcast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerMessageService {

    private static final int MAX_BODY_CHARS = 10_000;
    private static final int MAX_ATTACHMENT_CHARS = 4_000_000;

    private final OwnerMessageRepository messageRepo;
    private final OwnerMessageReadRepository readRepo;
    private final OwnerMessageReplyRepository replyRepo;
    private final UserRepository userRepo;

    // ─── Owner ────────────────────────────────────────────────────────────────

    /** Broadcast one message to all super admins. */
    @Transactional
    public MessageDto sendBroadcast(User owner, SendMessageRequest req) {
        OwnerMessageType type = validate(req);
        OwnerMessage saved = messageRepo.save(OwnerMessage.builder()
                .senderId(owner.getId())
                .body(blankToNull(req.body()))
                .msgType(type)
                .attachmentUrl(blankToNull(req.attachmentUrl()))
                .attachmentName(blankToNull(req.attachmentName()))
                .linkUrl(blankToNull(req.linkUrl()))
                .build());
        log.info("sendBroadcast: messageId='{}' senderId='{}' type='{}'", saved.getId(), owner.getId(), type);
        return toMessageDto(saved, displayName(owner), 0, totalSuperAdmins(), 0, null);
    }

    /** All broadcasts, newest first, with read / reply counts. */
    public List<MessageDto> listForOwner() {
        long total = totalSuperAdmins();
        return messageRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(m -> toMessageDto(m, senderName(m.getSenderId()),
                        readRepo.countByMessageId(m.getId()), total,
                        replyRepo.countByMessageId(m.getId()), null))
                .toList();
    }

    /** One broadcast + its replies (oldest first) + who has read it. */
    public MessageDetailDto detailForOwner(UUID id) {
        OwnerMessage m = messageRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Message not found."));
        long total = totalSuperAdmins();
        MessageDto dto = toMessageDto(m, senderName(m.getSenderId()),
                readRepo.countByMessageId(m.getId()), total,
                replyRepo.countByMessageId(m.getId()), null);
        List<ReplyDto> replies = replyRepo.findByMessageIdOrderByCreatedAtAsc(id).stream()
                .map(this::toReplyDto)
                .toList();
        List<ReadByDto> readBy = readRepo.findByMessageIdOrderByReadAtAsc(id).stream()
                .map(this::toReadByDto)
                .toList();
        return new MessageDetailDto(dto, replies, readBy);
    }

    // ─── Super admin ──────────────────────────────────────────────────────────

    /** All broadcasts, newest first, with an unread flag for this super admin. */
    public List<MessageDto> listForSuperAdmin(User sa) {
        long total = totalSuperAdmins();
        return messageRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(m -> toMessageDto(m, senderName(m.getSenderId()),
                        readRepo.countByMessageId(m.getId()), total,
                        replyRepo.countByMessageId(m.getId()),
                        !readRepo.existsByMessageIdAndSuperAdminId(m.getId(), sa.getId())))
                .toList();
    }

    /** How many broadcasts this super admin has not read yet. */
    public UnreadCountDto unreadCountFor(User sa) {
        long unread = messageRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(m -> !readRepo.existsByMessageIdAndSuperAdminId(m.getId(), sa.getId()))
                .count();
        return new UnreadCountDto(unread);
    }

    /** One broadcast + its replies (oldest first). readBy is null here. */
    public MessageDetailDto detailForSuperAdmin(User sa, UUID id) {
        OwnerMessage m = messageRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Message not found."));
        long total = totalSuperAdmins();
        boolean unread = !readRepo.existsByMessageIdAndSuperAdminId(m.getId(), sa.getId());
        MessageDto dto = toMessageDto(m, senderName(m.getSenderId()),
                readRepo.countByMessageId(m.getId()), total,
                replyRepo.countByMessageId(m.getId()), unread);
        List<ReplyDto> replies = replyRepo.findByMessageIdOrderByCreatedAtAsc(id).stream()
                .map(this::toReplyDto)
                .toList();
        return new MessageDetailDto(dto, replies, null);
    }

    /** Record that this super admin read the message. Idempotent. */
    @Transactional
    public void markRead(User sa, UUID id) {
        OwnerMessage m = messageRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Message not found."));
        if (readRepo.findByMessageIdAndSuperAdminId(m.getId(), sa.getId()).isEmpty()) {
            readRepo.save(OwnerMessageRead.builder()
                    .messageId(m.getId())
                    .superAdminId(sa.getId())
                    .build());
            log.info("markRead: messageId='{}' superAdminId='{}'", m.getId(), sa.getId());
        }
    }

    /** Reply to a broadcast. The reply goes to the site owner only. */
    @Transactional
    public ReplyDto reply(User sa, UUID id, SendMessageRequest req) {
        OwnerMessage m = messageRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Message not found."));
        OwnerMessageType type = validate(req);
        OwnerMessageReply saved = replyRepo.save(OwnerMessageReply.builder()
                .messageId(m.getId())
                .senderId(sa.getId())
                .body(blankToNull(req.body()))
                .msgType(type)
                .attachmentUrl(blankToNull(req.attachmentUrl()))
                .attachmentName(blankToNull(req.attachmentName()))
                .linkUrl(blankToNull(req.linkUrl()))
                .build());
        log.info("reply: replyId='{}' messageId='{}' senderId='{}' type='{}'", saved.getId(), m.getId(), sa.getId(), type);
        return toReplyDto(saved);
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Shared by send + reply. Returns the parsed message type.
     * Accepts the type case-insensitively ("text" or "TEXT").
     */
    private OwnerMessageType validate(SendMessageRequest req) {
        if (req == null) throw ApiException.badRequest("Message is empty.");
        OwnerMessageType type = null;
        if (req.msgType() != null && !req.msgType().isBlank()) {
            try {
                type = OwnerMessageType.valueOf(req.msgType().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Invalid message type.");
            }
        }
        if (type == null) throw ApiException.badRequest("Invalid message type.");
        boolean hasBody = req.body() != null && !req.body().isBlank();
        boolean hasAttachment = req.attachmentUrl() != null && !req.attachmentUrl().isBlank();
        boolean hasLink = req.linkUrl() != null && !req.linkUrl().isBlank();
        if (!hasBody && !hasAttachment && !hasLink) throw ApiException.badRequest("Message is empty.");
        if (hasBody && req.body().length() > MAX_BODY_CHARS) {
            throw ApiException.badRequest("Message is too long (max 10000 characters).");
        }
        if (hasAttachment && req.attachmentUrl().length() > MAX_ATTACHMENT_CHARS) {
            throw ApiException.badRequest("Attachment is too large (max 3MB).");
        }
        if (hasLink) {
            String link = req.linkUrl().trim();
            if (!link.startsWith("http://") && !link.startsWith("https://")) {
                throw ApiException.badRequest("Link must start with http:// or https://.");
            }
        }
        return type;
    }

    // ─── Mapping + names ──────────────────────────────────────────────────────

    private MessageDto toMessageDto(OwnerMessage m, String senderName,
                                    long readCount, long totalSAs, long replyCount, Boolean unread) {
        return new MessageDto(
                m.getId(), m.getSenderId(), senderName, m.getBody(),
                m.getMsgType() == null ? null : m.getMsgType().name(),
                m.getAttachmentUrl(), m.getAttachmentName(), m.getLinkUrl(),
                m.getCreatedAt(), readCount, totalSAs, replyCount, unread);
    }

    private ReplyDto toReplyDto(OwnerMessageReply r) {
        User sender = r.getSenderId() == null ? null : userRepo.findById(r.getSenderId()).orElse(null);
        return new ReplyDto(
                r.getId(), r.getMessageId(), r.getSenderId(),
                sender == null ? "Unknown" : displayName(sender),
                sender == null ? null : sender.getEmail(),
                r.getBody(), r.getMsgType() == null ? null : r.getMsgType().name(),
                r.getAttachmentUrl(), r.getAttachmentName(), r.getLinkUrl(),
                r.getCreatedAt());
    }

    private ReadByDto toReadByDto(OwnerMessageRead r) {
        User sa = r.getSuperAdminId() == null ? null : userRepo.findById(r.getSuperAdminId()).orElse(null);
        return new ReadByDto(
                r.getSuperAdminId(),
                sa == null ? "Unknown" : displayName(sa),
                sa == null ? null : sa.getEmail(),
                r.getReadAt());
    }

    private long totalSuperAdmins() {
        return userRepo.findAllByRole(UserRole.SUPER_ADMIN).size();
    }

    /** Display name of a broadcast sender — "Site owner" if the user is gone. */
    private String senderName(UUID senderId) {
        if (senderId == null) return "Site owner";
        return userRepo.findById(senderId).map(this::displayName).orElse("Site owner");
    }

    /** First + last name, else email, else "Unknown". */
    private String displayName(User u) {
        String name = ((u.getFirstName() == null ? "" : u.getFirstName())
                + " " + (u.getLastName() == null ? "" : u.getLastName())).trim();
        if (!name.isBlank()) return name;
        if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
        return "Unknown";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
