package com.speedbet.api.media;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton platform settings row for Cloudinary proof uploads (one row,
 * fixed id). The site owner sets the Cloudinary cloud name + unsigned
 * upload preset from the site-owner panel; the frontend reads them from
 * the public endpoint and uploads proof screenshots (Nigeria manual bank
 * deposits, Ghana manual MoMo deposits, message attachments) straight to
 * Cloudinary. Neither value is a secret: an unsigned upload preset is
 * designed to be public, so the cloud name + preset are served to every
 * client without auth. A missing row reads as "not configured" and the
 * frontend falls back to ImgBB until the site owner sets Cloudinary.
 * Table is auto-created by Hibernate (ddl-auto=update) — no SQL needed.
 */
@Entity
@Table(name = "cloudinary_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CloudinarySetting {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Id
    private UUID id;

    /** Cloudinary cloud name (e.g. "dabc123"). Public identifier. */
    @Column(name = "cloud_name", length = 100)
    private String cloudName;

    /** Unsigned upload preset name (e.g. "7up_proofs"). Public identifier. */
    @Column(name = "upload_preset", length = 100)
    private String uploadPreset;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the site owner who last saved the settings. */
    @Column(name = "updated_by")
    private String updatedBy;
}
