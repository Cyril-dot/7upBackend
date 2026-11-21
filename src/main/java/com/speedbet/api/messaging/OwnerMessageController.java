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
import java.util.UUID;

/**
 * SITE OWNER endpoints — broadcast messages to ALL super admins.
 *
 * Only SITE_OWNER can reach these routes (URL guard in SecurityConfig +
 * the class-level @PreAuthorize below).
 */
@Slf4j
@RestController
@RequestMapping("/api/site-owner/messages")
@PreAuthorize("hasRole('SITE_OWNER')")
@RequiredArgsConstructor
public class OwnerMessageController {

    private final OwnerMessageService messageService;

    /**
     * POST /api/site-owner/messages
     * Broadcast one message to all super admins.
     */
    @PostMapping("")
    public ResponseEntity<ApiResponse<MessageDto>> send(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("sendBroadcast: senderId='{}' type='{}'", user.getId(), request.msgType());
        return ResponseEntity.ok(ApiResponse.ok(messageService.sendBroadcast(user, request)));
    }

    /**
     * GET /api/site-owner/messages
     * All broadcasts, newest first, with read / reply counts.
     */
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<MessageDto>>> list(
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.ok(messageService.listForOwner()));
    }

    /**
     * GET /api/site-owner/messages/{id}
     * One broadcast + its replies + who has read it.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageDetailDto>> detail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(ApiResponse.ok(messageService.detailForOwner(id)));
    }
}
