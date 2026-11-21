package com.speedbet.api.messaging;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.messaging.OwnerMessageDtos.*;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SUPER ADMIN endpoints — read the site owner's broadcasts and reply.
 *
 * Only SUPER_ADMIN can reach these routes (URL guard in SecurityConfig +
 * the class-level @PreAuthorize below). Every super admin sees every
 * broadcast; replies go to the site owner only.
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/messages")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminMessageController {

    private final OwnerMessageService messageService;

    /**
     * GET /api/super-admin/messages
     * All broadcasts, newest first, with an unread flag for this super admin.
     */
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<MessageDto>>> list(
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.ok(messageService.listForSuperAdmin(user)));
    }

    /**
     * GET /api/super-admin/messages/unread-count
     * How many broadcasts this super admin has not read yet.
     * Declared before "/{id}" so the literal path wins over the variable.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountDto>> unreadCount(
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.ok(messageService.unreadCountFor(user)));
    }

    /**
     * GET /api/super-admin/messages/{id}
     * One broadcast + its replies. readBy is null here.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageDetailDto>> detail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.ok(messageService.detailForSuperAdmin(user, id)));
    }

    /**
     * POST /api/super-admin/messages/{id}/read
     * Record that this super admin read the message. Idempotent.
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {

        messageService.markRead(user, id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("ok", true)));
    }

    /**
     * POST /api/super-admin/messages/{id}/replies
     * Reply to a broadcast. The reply goes to the site owner only.
     */
    @PostMapping("/{id}/replies")
    public ResponseEntity<ApiResponse<ReplyDto>> reply(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("reply: messageId='{}' senderId='{}' type='{}'", id, user.getId(), request.msgType());
        return ResponseEntity.ok(ApiResponse.ok(messageService.reply(user, id, request)));
    }
}
