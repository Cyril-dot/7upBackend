package com.speedbet.api.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The ACTIVE payment gateways for one super admin's branch.
 *
 * Only the site owner ever writes this row (approve a super admin's
 * request, or set a branch directly). A super admin's own choice lives
 * in GatewayRequest and has NO effect on deposits until the owner
 * approves it — that is the measure the owner asked for.
 *
 * Gateways are plain strings, deliberately NOT a Java enum: a new
 * gateway value must never be blocked by a database check constraint
 * (see the users_role_check lesson — ddl-auto=update does not widen
 * pre-existing constraints). GatewayService validates every value
 * against the allowed lists before it is stored.
 */
@Entity
@Table(name = "branch_gateway_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BranchGatewaySetting {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "super_admin_id", nullable = false, unique = true)
    private UUID superAdminId;

    /** Ghana deposit gateway. Allowed: AKWAPAY, GMP. */
    @Column(name = "gh_gateway", nullable = false, length = 30)
    @Builder.Default
    private String ghGateway = "AKWAPAY";

    /** Nigeria deposit gateway. Allowed: GMP, KORAPAY. */
    @Column(name = "ng_gateway", nullable = false, length = 30)
    @Builder.Default
    private String ngGateway = "GMP";

    /** The site owner who last set this row. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", updatable = false, nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
