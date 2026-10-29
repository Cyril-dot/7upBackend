package com.speedbet.api.match;

import com.speedbet.api.bet.SettlementEngine;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.Match;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only REST controller for match lifecycle management.
 *
 * Every endpoint:
 *   1. Requires the ADMIN or SUPER_ADMIN role (enforced by @PreAuthorize).
 *   2. Receives the authenticated admin via @AuthenticationPrincipal — the
 *      identity is never taken from the request body.
 *   3. Delegates to AdminMatchService which enforces per-admin ownership,
 *      meaning each admin can only see and edit their own matches.
 *
 * Base path: /api/admin/matches
 *
 *   POST   /                  — create a match (odds auto-generated)
 *   GET    /                  — list MY matches
 *   GET    /{id}              — get one of MY matches
 *   PATCH  /{id}/status       — transition status (MY match only)
 *   PATCH  /{id}/score        — update live score (MY match only, non-FINISHED)
 *   POST   /{id}/settle       — settle a FINISHED match's bets on demand (MY match only)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/matches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminMatchController {

    private final AdminMatchService adminMatchService;
    private final SettlementEngine settlementEngine;
    private final MatchService matchService;

    // ── Create ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Match> createMatch(
            @Valid @RequestBody AdminMatchRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: createMatch home='{}' away='{}'",
                admin.getId(), req.getHomeTeam(), req.getAwayTeam());
        Match created = adminMatchService.createMatch(req, admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── Read (scoped to this admin only) ──────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Match>> listMyMatches(
            @AuthenticationPrincipal User admin) {

        return ResponseEntity.ok(adminMatchService.getMyMatches(admin));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Match> getMyMatch(
            @PathVariable String id,
            @AuthenticationPrincipal User admin) {

        return ResponseEntity.ok(adminMatchService.getMyMatch(id, admin));
    }

    // ── Status transition ─────────────────────────────────────────────────

    /**
     * Moves the match through the state machine:
     *   SCHEDULED → LIVE → HALF_TIME → SECOND_HALF → FINISHED
     *
     * Returns 400 if the transition is invalid or the match is already FINISHED.
     * Returns 404 if the match doesn't exist or belongs to another admin.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Match> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdminStatusUpdateRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: updateStatus matchId={} target={}", admin.getId(), id, req.getStatus());
        return ResponseEntity.ok(adminMatchService.updateStatus(id, req, admin));
    }

    // ── Score update ──────────────────────────────────────────────────────

    /**
     * Updates the score of a live match.
     * Blocked for FINISHED and SCHEDULED matches.
     * No match events (goalscorers, cards, substitutions) are accepted.
     */
    @PatchMapping("/{id}/score")
    public ResponseEntity<Match> updateScore(
            @PathVariable UUID id,
            @Valid @RequestBody AdminScoreUpdateRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: updateScore matchId={} {}:{} minute={}",
                admin.getId(), id, req.getScoreHome(), req.getScoreAway(), req.getMinutePlayed());
        return ResponseEntity.ok(adminMatchService.updateScore(id, req, admin));
    }

    // ── Manual settlement ────────────────────────────────────────────────

    /**
     * Settles a finished match's bets on demand — the same engine pass the
     * 60s scheduler runs, followed by the mark-settled + late-bet second pass.
     * Returns 400 if the match is not FINISHED or has no final score recorded.
     * Returns 404 if the match doesn't exist or belongs to another admin.
     * Re-running on an already-settled match is a harmless no-op (no pending
     * bets remain, so the tally comes back zero).
     */
    @PostMapping("/{id}/settle")
    public ResponseEntity<Map<String, Object>> settleMatch(
            @PathVariable UUID id,
            @AuthenticationPrincipal User admin) {

        Match match = adminMatchService.getMyMatch(id, admin.getId());
        if (!"FINISHED".equals(match.getStatus())) {
            throw ApiException.badRequest("Match is not finished yet");
        }
        if (match.getScoreHome() == null || match.getScoreAway() == null) {
            throw ApiException.badRequest("Match has no final score recorded — cannot settle");
        }
        log.info("Admin[{}]: settleMatch matchId={} score={}:{}",
                admin.getId(), id, match.getScoreHome(), match.getScoreAway());
        SettlementEngine.Tally tally = settlementEngine.settleMatch(match);
        matchService.markSettled(id.toString());
        SettlementEngine.Tally lateTally = settlementEngine.settleMatch(match);
        log.info("Admin[{}]: settleMatch matchId={} done — {} + late {}",
                admin.getId(), id, tally, lateTally);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("won", tally.won + lateTally.won);
        result.put("lost", tally.lost + lateTally.lost);
        result.put("voided", tally.voided + lateTally.voided);
        result.put("deferred", tally.deferred + lateTally.deferred);
        result.put("skipped", tally.skipped + lateTally.skipped);
        result.put("failed", tally.failed + lateTally.failed);
        result.put("settled", true);
        return ResponseEntity.ok(result);
    }
}