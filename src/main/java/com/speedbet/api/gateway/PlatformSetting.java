package com.speedbet.api.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Platform-wide settings set by the site owner in the app — the default
 * gateway a branch falls back to when it has no explicit setting
 * (v216). Singleton row (id = SINGLETON_ID). Hibernate ddl-auto=update
 * creates the table — no SQL needed. A missing row / blank value means
 * the hardcoded DEFAULT_GH / DEFAULT_NG in GatewayService still apply,
 * so a fresh DB behaves exactly as before until the owner picks one.
 */
@Entity
@Table(name = "platform_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformSetting {

    public static final UUID SINGLETON_ID = UUID.fromString("33333333-4444-5555-6666-777777777777");

    @Id
    private UUID id;

    @Column(name = "default_gh_gateway", length = 30)
    private String defaultGhGateway;

    @Column(name = "default_ng_gateway", length = 30)
    private String defaultNgGateway;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
