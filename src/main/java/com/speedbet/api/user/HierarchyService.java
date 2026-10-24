package com.speedbet.api.user;

import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.referral.ReferralRepository;
import com.speedbet.api.wallet.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The ownership tree, in one place:
 *
 *   SITE_OWNER ──createdByAdminId──▶ SUPER_ADMIN ──createdByAdminId──▶ ADMIN
 *   ──referral link──▶ USER
 *
 * A super admin's SUBTREE is: their admins + every user who signed up
 * through one of those admins' referral links. Every super-admin-scoped
 * query and guard in the codebase goes through here, so "whose data is
 * this" can never drift between endpoints.
 *
 * The SITE OWNER is deliberately NOT part of any subtree: the owner sees
 * the whole platform (unscoped queries), never a filtered view.
 */
@Service
@RequiredArgsConstructor
public class HierarchyService {

    private final UserRepository userRepo;
    private final ReferralLinkRepository linkRepo;
    private final ReferralRepository referralRepo;
    private final WalletRepository walletRepo;

    /** Admins created by one super admin. */
    public List<User> adminsOfSuperAdmin(UUID superAdminId) {
        return userRepo.findByRoleAndCreatedByAdminId(UserRole.ADMIN, superAdminId);
    }

    /** Ids of the admins created by one super admin. */
    public Set<UUID> adminIdsOfSuperAdmin(UUID superAdminId) {
        return adminsOfSuperAdmin(superAdminId).stream()
                .map(User::getId).collect(Collectors.toSet());
    }

    /** Super admins created by one site owner. */
    public List<User> superAdminsOfOwner(UUID ownerId) {
        return userRepo.findByRoleAndCreatedByAdminId(UserRole.SUPER_ADMIN, ownerId);
    }

    /**
     * Every end-user inside a super admin's subtree: users attributed to a
     * referral link owned by one of the super admin's admins.
     */
    public Set<UUID> subtreeUserIds(UUID superAdminId) {
        Set<UUID> adminIds = adminIdsOfSuperAdmin(superAdminId);
        if (adminIds.isEmpty()) return Set.of();
        List<UUID> linkIds = new ArrayList<>();
        for (UUID adminId : adminIds) {
            linkRepo.findByAdminId(adminId).forEach(l -> linkIds.add(l.getId()));
        }
        if (linkIds.isEmpty()) return Set.of();
        return referralRepo.findByLinkIdIn(linkIds).stream()
                .map(r -> r.getUserId()).collect(Collectors.toSet());
    }

    /** Everyone a super admin may see as a "user": subtree users + own admins. */
    public Set<UUID> visibleUserIds(UUID superAdminId) {
        Set<UUID> ids = new HashSet<>(subtreeUserIds(superAdminId));
        ids.addAll(adminIdsOfSuperAdmin(superAdminId));
        return ids;
    }

    /** Wallets belonging to everyone in the subtree (users + admins). */
    public Set<UUID> subtreeWalletIds(UUID superAdminId) {
        Set<UUID> userIds = visibleUserIds(superAdminId);
        if (userIds.isEmpty()) return Set.of();
        return walletRepo.findAllByUserIdIn(userIds).stream()
                .map(w -> w.getId()).collect(Collectors.toSet());
    }

    public long subtreeUserCount(UUID superAdminId) {
        return subtreeUserIds(superAdminId).size();
    }

    /** True when the admin was created by this super admin. */
    public boolean isAdminOf(UUID superAdminId, UUID adminId) {
        if (superAdminId == null || adminId == null) return false;
        return userRepo.findById(adminId)
                .map(u -> u.getRole() == UserRole.ADMIN
                        && superAdminId.equals(u.getCreatedByAdminId()))
                .orElse(false);
    }

    /** True when the user is inside the super admin's subtree (or is their admin). */
    public boolean isUserVisibleTo(UUID superAdminId, UUID userId) {
        if (superAdminId == null || userId == null) return false;
        return visibleUserIds(superAdminId).contains(userId);
    }

    /**
     * The super admin an admin belongs to, if any. Empty when the admin was
     * created by the site owner directly (owner-direct admins belong to no
     * super admin, so no super-admin commission applies to their users).
     */
    public Optional<UUID> superAdminOfAdmin(UUID adminId) {
        return userRepo.findById(adminId)
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .map(User::getCreatedByAdminId)
                .filter(parentId -> userRepo.findById(parentId)
                        .map(p -> p.getRole() == UserRole.SUPER_ADMIN)
                        .orElse(false));
    }
}
