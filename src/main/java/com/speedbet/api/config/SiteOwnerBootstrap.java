package com.speedbet.api.config;

import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * First-site-owner bootstrap — deliberately explicit, never automatic.
 *
 * On startup, if {@code app.bootstrap.site-owner-email} is set (env
 * BOOTSTRAP_SITE_OWNER_EMAIL) AND no SITE_OWNER exists yet AND a user with
 * that email exists, that user is promoted to SITE_OWNER.
 *
 * Runs after UserRoleConstraintSync (@Order 2 vs 1) so the users_role_check
 * constraint already allows SITE_OWNER before this promotion is attempted.
 *
 * Operator flow: register a normal account for the owner, set the env var
 * to that email, start the app once, then REMOVE the env var. Without the
 * env var this runner does nothing, and once an owner exists it never
 * promotes anyone again — so nobody can ever self-promote by registering.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class SiteOwnerBootstrap implements ApplicationRunner {

    private final UserRepository userRepo;

    @Value("${app.bootstrap.site-owner-email:}")
    private String bootstrapEmail;

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()) {
            return;
        }
        if (!userRepo.findAllByRole(UserRole.SITE_OWNER).isEmpty()) {
            log.info("SiteOwnerBootstrap: a site owner already exists — skipping");
            return;
        }
        var user = userRepo.findByEmail(bootstrapEmail.toLowerCase().trim()).orElse(null);
        if (user == null) {
            log.warn("SiteOwnerBootstrap: no user with email '{}' — site owner NOT created. "
                    + "Register that account first, then restart.", bootstrapEmail);
            return;
        }
        user.setRole(UserRole.SITE_OWNER);
        userRepo.save(user);
        log.info("SiteOwnerBootstrap: user '{}' promoted to SITE_OWNER. "
                + "Remove BOOTSTRAP_SITE_OWNER_EMAIL now.", user.getEmail());
    }
}
