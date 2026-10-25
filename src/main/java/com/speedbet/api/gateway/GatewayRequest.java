package com.speedbet.api.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A super admin's DECISION about their branch's gateways — a request
 * only. It changes nothing on its own: deposits keep using the
 * branch's active BranchGatewaySetting until the site owner approves
 * this request (or sets the branch directly, which supersedes it).
 *
 * Statuses: PENDING, APPROVED, REJECTED. Only one PENDING request
 * per super admin is allowed, so a stale approve can never resurrect
 * an old choice after the owner already set something newer.
 */
@Entity
@Table(name = "gateway_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GatewayRequest {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "super_admin_id", nullable = false)
    private UUID superAdminId;

    @Column(name = "gh_gateway", nullable = false, length = 30)
    private String ghGateway;

    @Column(name = "ng_gateway", nullable = false, length = 30)
    private String ngGateway;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
