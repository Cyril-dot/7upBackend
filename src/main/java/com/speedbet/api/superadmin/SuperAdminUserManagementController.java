package com.speedbet.api.superadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/super-admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminUserManagementController {

    private final SuperAdminUserManagementService userManagementService;
    private final com.speedbet.api.user.HierarchyService hierarchy;

    /**
     * Branch guard: a super admin may only change users inside their own
     * subtree (or their own admins). The site owner passes through.
     * (The service still receives the actor id for its audit trail.)
     */
    private void requireVisible(com.speedbet.api.user.User actor, java.util.UUID userId) {
        if (actor.getRole() == com.speedbet.api.user.UserRole.SITE_OWNER) return;
        if (!hierarchy.isUserVisibleTo(actor.getId(), userId)) {
            throw com.speedbet.api.common.ApiException.forbidden("User does not belong to your branch");
        }
    }

    /**
     * PATCH /api/v1/super-admin/users/{userId}/deactivate
     */
    @PatchMapping("/{userId}/deactivate")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> deactivateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal com.speedbet.api.user.User actor) {
        requireVisible(actor, userId);
        UUID adminId = actor.getId();

        log.info("PATCH /super-admin/users/{}/deactivate — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.deactivateUser(userId, adminId));
    }

    /**
     * PATCH /api/v1/super-admin/users/{userId}/activate
     */
    @PatchMapping("/{userId}/activate")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> activateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal com.speedbet.api.user.User actor) {
        requireVisible(actor, userId);
        UUID adminId = actor.getId();

        log.info("PATCH /super-admin/users/{}/activate — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.activateUser(userId, adminId));
    }

    /**
     * PATCH /api/v1/super-admin/users/{userId}/toggle-status
     */
    @PatchMapping("/{userId}/toggle-status")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> toggleUserStatus(
            @PathVariable UUID userId,
            @AuthenticationPrincipal com.speedbet.api.user.User actor) {
        requireVisible(actor, userId);
        UUID adminId = actor.getId();

        log.info("PATCH /super-admin/users/{}/toggle-status — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.toggleUserStatus(userId, adminId));
    }
}