package com.speedbet.api.media;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloudinary proof-upload settings. The site owner sets the cloud name +
 * unsigned upload preset once (site-owner panel); the frontend reads them
 * from the public endpoint and uploads proof images straight to
 * Cloudinary. Cloud name + upload preset are public identifiers for an
 * unsigned preset (no secret is ever involved), so the public read needs
 * no auth. A missing/blank setting reads as not configured.
 */
@RestController
@RequiredArgsConstructor
public class CloudinaryController {

    private final CloudinarySettingRepository settingRepo;

    private Map<String, Object> view(CloudinarySetting s) {
        var out = new LinkedHashMap<String, Object>();
        boolean set = s != null
                && s.getCloudName() != null && !s.getCloudName().isBlank()
                && s.getUploadPreset() != null && !s.getUploadPreset().isBlank();
        out.put("configured", set);
        out.put("cloudName", set ? s.getCloudName() : null);
        out.put("uploadPreset", set ? s.getUploadPreset() : null);
        return out;
    }

    /** Public read: what the frontend needs to upload a proof image. */
    @GetMapping("/api/public/cloudinary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicSettings() {
        var s = settingRepo.findById(CloudinarySetting.SINGLETON_ID).orElse(null);
        return ResponseEntity.ok(ApiResponse.ok(view(s)));
    }

    /** Site owner read: current settings (same values as the public read). */
    @GetMapping("/api/site-owner/cloudinary")
    @PreAuthorize("hasRole('SITE_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ownerSettings(
            @AuthenticationPrincipal User actor) {
        if (actor == null) throw ApiException.forbidden("Sign in first.");
        var s = settingRepo.findById(CloudinarySetting.SINGLETON_ID).orElse(null);
        return ResponseEntity.ok(ApiResponse.ok(view(s)));
    }

    /**
     * Site owner write. Body: {"cloudName": "...", "uploadPreset": "..."}
     * to set, or {"clear": true} to remove and go back to unconfigured
     * (frontend falls back to ImgBB until Cloudinary is set again).
     */
    @PutMapping("/api/site-owner/cloudinary")
    @PreAuthorize("hasRole('SITE_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ownerSave(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, Object> req) {
        if (actor == null) throw ApiException.forbidden("Sign in first.");
        boolean clear = Boolean.TRUE.equals(req.get("clear"));
        if (clear) {
            settingRepo.deleteById(CloudinarySetting.SINGLETON_ID);
            return ResponseEntity.ok(ApiResponse.ok(view(null),
                    "Cloudinary settings removed. Proof uploads fall back to ImgBB until you set Cloudinary again."));
        }
        String cloudName = req.get("cloudName") == null ? "" : String.valueOf(req.get("cloudName")).trim();
        String uploadPreset = req.get("uploadPreset") == null ? "" : String.valueOf(req.get("uploadPreset")).trim();
        if (cloudName.isBlank() || uploadPreset.isBlank()) {
            throw ApiException.badRequest("Enter both the Cloudinary cloud name and the unsigned upload preset.");
        }
        var s = settingRepo.findById(CloudinarySetting.SINGLETON_ID)
                .orElseGet(() -> CloudinarySetting.builder().id(CloudinarySetting.SINGLETON_ID).build());
        s.setCloudName(cloudName);
        s.setUploadPreset(uploadPreset);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actor.getEmail());
        settingRepo.save(s);
        return ResponseEntity.ok(ApiResponse.ok(view(s),
                "Cloudinary settings saved. Proof uploads now go to Cloudinary."));
    }
}
