package com.speedbet.api.config;

import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps the {@code users.role} CHECK constraint in sync with the
 * {@link UserRole} enum.
 *
 * The constraint was created on the database before SITE_OWNER existed, so
 * promoting a user to SITE_OWNER was rejected at save time with:
 *   new row for relation "users" violates check constraint
 *   "users_role_check"
 * Because SiteOwnerBootstrap runs on every boot while its env var is set,
 * that rejection killed the whole application context and put Railway
 * into a crash loop (every API call 502).
 *
 * Rebuilding it from the enum on every boot means newly added roles can
 * never break promotion again. This runner MUST run before
 * SiteOwnerBootstrap — see the {@code @Order} values on both classes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class UserRoleConstraintSync implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        String allowed = Arrays.stream(UserRole.values())
                .map(r -> "'" + r.name() + "'")
                .collect(Collectors.joining(","));
        try {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check");
            jdbc.execute("ALTER TABLE users ADD CONSTRAINT users_role_check "
                    + "CHECK (role IN (" + allowed + "))");
            log.info("users_role_check synced ({} roles)", UserRole.values().length);
        } catch (Exception e) {
            // Never block startup — log loudly instead. Note: if this sync
            // fails, SiteOwnerBootstrap promotion will fail the same way
            // it did before, so this error must not be ignored in the logs.
            log.error("FAILED to sync users_role_check — SITE_OWNER promotion may be rejected: {}",
                    e.getMessage());
        }
    }
}
