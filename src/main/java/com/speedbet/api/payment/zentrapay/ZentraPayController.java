package com.speedbet.api.payment.zentrapay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.gateway.GatewayService;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZentraPay (https://zentrapay-lyart.vercel.app) mobile-money deposit integration.
 *
 * Flow (direct MoMo prompt — no hosted checkout, no browser widget):
 *  1. POST /api/wallet/deposit/zentrapay/init {amount, phone, network?}
 *     -> ZentraPay POST /v1/charges/charge sends the USSD/STK prompt
 *        straight to the customer's handset. Returns {reference, status:
 *        "PENDING"}. The reference is OURS (DEP_<userId>_<random>) so the
 *        userId is recoverable from every later call and webhook.
 *  2. Customer approves on the phone with their MoMo PIN; ZentraPay POSTs
 *     charge.success to /api/webhooks/zentrapay.
 *  3. GET  /api/wallet/deposit/zentrapay/status?ref=... -> frontend poll;
 *     server-to-server GET /v1/charges/verify/:reference, credits on
 *     SUCCESS (idempotent — duplicate references 409 and are swallowed).
 *
 * Why server-to-server (unlike RushPay): ZentraPay's /charge is a plain
 * Bearer-auth server API — no browser-scoped widget session — so the
 * secret key never leaves this backend. Do not move the charge call to
 * the browser.
 *
 * Security model (mirrors AlphaPay):
 *  - Webhook identity = HMAC-SHA256 over the raw body, keyed with the
 *    webhook secret (header x-zentrapay-signature). The secret is
 *    resolved per payment from the reference: branch key, else platform
 *    key, else env.
 *  - Even after the signature passes, we NEVER trust the payload: we call
 *    ZentraPay's verify API and credit only the amount/status it returns
 *    server-to-server.
 *  - The reference embeds the userId, and the status endpoint asserts it
 *    belongs to the authenticated user, so one user can't trigger or
 *    inspect another user's deposit.
 *
 * Money rule: the wallet is credited the FULL requested amount; 7UP
 * absorbs the ZentraPay fee (same as PayTeller). The verify response
 * carries fee/net_amount for the ledger, but credit uses amount.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ZentraPayController {

    // DEP_<32 hex userId without dashes>_<12 hex random> (same as AlphaPay)
    private static final Pattern REF_PATTERN = Pattern.compile("^DEP_([0-9a-f]{32})_[0-9a-f]{12}$");

    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WalletService    walletService;
    private final ReferralService  referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper     objectMapper;
    private final GatewayService   gatewayService;

    @Value("${app.zentrapay.secret-key:}")          private String     secretKey;
    @Value("${app.zentrapay.webhook-secret:}")      private String     webhookSecret;
    @Value("${app.zentrapay.base-url:https://zentrapay-lyart.vercel.app}") private String baseUrl;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── Diagnostics (v186) ───────────────────────────────────────────────
    // The last init failure on this instance, so the super admin can see
    // WHY the last deposit died instead of the generic "internal error".
    // No secrets, no full phone — masked like the logs. Lost on restart.
    // Guide v2 (Part 12 Step 2): startup check — log the ZentraPay host +
    // gateway mode once at boot so a dead host or mock mode is visible in
    // the Railway logs from the first line. Never breaks startup.
    @PostConstruct
    public void logZentraPayHealthAtStartup() {
        try {
            var h = probeHealth();
            log.info("[ZentraPay][startup] baseUrl='{}' reachable={} status={} mode={} error={}",
                    baseUrl, h.get("reachable"), h.get("status"), h.get("mode"), h.get("error"));
        } catch (Exception e) {
            log.warn("[ZentraPay][startup] health check failed — {}", e.getMessage());
        }
    }

    private static final AtomicReference<Map<String, Object>> LAST_INIT_FAILURE = new AtomicReference<>();

    private static void recordInitFailure(String kind, String detail, String phoneMasked) {
        var m = new LinkedHashMap<String, Object>();
        m.put("at", Instant.now().toString());
        m.put("kind", kind);
        m.put("detail", detail);
        if (phoneMasked != null) m.put("phone", phoneMasked);
        LAST_INIT_FAILURE.set(m);
    }

    private static String last4(String v) {
        if (v == null || v.isBlank()) return null;
        return v.length() <= 4 ? v : v.substring(v.length() - 4);
    }

    private String apiHost() {
        try { return URI.create(baseUrl).getHost(); } catch (Exception e) { return "the ZentraPay API"; }
    }

    /** Short plain-language reason for an upstream failure (no stack traces leave the server). */
    private String upstreamReason(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException)
                return "Could not reach ZentraPay — could not resolve " + apiHost();
            if (t instanceof ConnectException)
                return "Could not reach ZentraPay — connection refused";
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException)
                return "ZentraPay did not answer in time (15s timeout)";
            if (t instanceof SSLException)
                return "Could not reach ZentraPay — secure connection failed";
            t = t.getCause();
        }
        if (e != null && (e.getClass().getSimpleName().contains("Decoding") || e.getClass().getSimpleName().contains("Json")))
            return "ZentraPay returned an unexpected response";
        return "ZentraPay is currently unavailable";
    }

    private ApiException upstreamUnavailable(Throwable e) {
        return new ApiException(upstreamReason(e) + ". Please try again.", HttpStatus.BAD_GATEWAY);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Charge email for EVERY deposit (owner's order, 21 Nov 2026): the
    // kevinbaahh address, not the player's own email — one known-good
    // address for all charges, so a bad/blank player email can never get
    // a charge rejected (same reason as the BluPay fallback email).
    private static final String CHARGE_EMAIL = "kbaah299@gmail.com";

    // Step 1 — Direct MoMo charge (prompt goes to the phone)
    // Body: { "amount": 50, "phone": "0241234567", "network": "MTN" (optional) }
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/zentrapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> init(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        gatewayService.requireDepositsEnabled(user);
        log.info("[ZentraPay][init] START — userId='{}'", user.getId());

        var amount = extractValidAmount(req);

        String phone = null;
        var rawPhone = req.get("phone");
        if (rawPhone != null && !rawPhone.toString().isBlank()) {
            phone = normalizeGhanaPhone(rawPhone.toString().trim());
            warnIfUnknownPrefix(phone);
        }
        if (phone == null) {
            throw ApiException.badRequest("Enter your MoMo number.");
        }

        // Network: explicit body value wins; else derive from the prefix;
        // unknown prefix -> omit and let ZentraPay auto-detect.
        String network = null;
        var rawNet = req.get("network");
        if (rawNet != null && !rawNet.toString().isBlank()) {
            network = normalizeNetwork(rawNet.toString().trim());
        }
        if (network == null) {
            network = networkForPrefix(phone.substring(0, 3));
        }

        var reference = buildReference(user.getId());

        var body = new LinkedHashMap<String, Object>();
        body.put("amount",       amount);              // BigDecimal, GHS, 2dp — NOT pesewas
        body.put("email",        CHARGE_EMAIL);
        body.put("phone",        phone);               // plain 10-digit local form
        if (network != null) body.put("network", network);
        body.put("reference",    reference);

        log.info("[ZentraPay][init] Calling charge — userId='{}' ref='{}' amountGHS={} phone='{}' network='{}'",
                user.getId(), reference, amount, maskPhone(phone), network);

        // Idempotency-Key IS the reference (guide's terminal example uses the
        // short reference as the key). The old "zentrapay_" + reference
        // (59 chars) was rejected by the gateway with
        // "Idempotency-Key must be 8-100 characters" and the charge came back
        // FAILED with no prompt; the 49-char reference-as-key was proven live
        // to return PENDING + a real MoMo prompt. A retried init still replays
        // safely instead of billing twice (same reference -> same key).
        Map<String, Object> response;
        try {
            response = callZentraPay(HttpMethod.POST, "/v1/charges/charge", body, true, "init",
                    secretKeyFor(user), reference);
        } catch (ApiException e) {
            if (e.getStatus().value() >= 500)
                recordInitFailure("upstream", e.getMessage(), maskPhone(phone));
            throw e;
        }

        var data = response.get("data");
        var status = data instanceof Map<?, ?> dm ? String.valueOf(dm.get("status")) : null;
        // Gateway failure reason (top-level message, else the data message).
        String failMsg = null;
        if (response.get("message") != null) failMsg = String.valueOf(response.get("message"));
        if (failMsg == null && data instanceof Map<?, ?> dm2 && dm2.get("message") != null)
            failMsg = String.valueOf(dm2.get("message"));
        if ("FAILED".equalsIgnoreCase(status)) {
            recordInitFailure("charge-failed", failMsg != null ? failMsg : "charge returned FAILED", maskPhone(phone));
        }
        log.info("[ZentraPay][init] DONE — userId='{}' ref='{}' status='{}'", user.getId(), reference, status);

        var out = new LinkedHashMap<String, Object>();
        out.put("reference", reference);
        out.put("status", status == null ? "PENDING" : status);
        if (failMsg != null) out.put("message", failMsg);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Status — frontend polling + fallback (webhooks retry, but the poll is
    // the primary credit path when the user stays on the deposit page).
    // Credits the wallet if ZentraPay says SUCCESS. Safe to call repeatedly:
    // walletService.credit() throws 409 on duplicate reference, which we swallow.
    // ═══════════════════════════════════════════════════════════════════════════
    @GetMapping("/api/wallet/deposit/zentrapay/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String ref) {

        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest("ref is required.");
        }
        assertReferenceBelongsTo(user, ref);
        log.info("[ZentraPay][status] userId='{}' ref='{}'", user.getId(), ref);

        Map<String, Object> verified;
        try {
            verified = zentraPayVerify(ref, secretKeyFor(user));
        } catch (ApiException e) {
            // Guide v2 Step 5: PENDING is normal — a charge created moments
            // ago may not be visible to verify yet (the gateway answers 404
            // "reference not found"). Treat it as PENDING so the frontend
            // poll keeps waiting instead of 400ing on every attempt.
            if (e.getStatus() == HttpStatus.BAD_REQUEST
                    && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("not found")) {
                log.info("[ZentraPay][status] verify 404 for ref='{}' — treating as PENDING", ref);
                var out = new LinkedHashMap<String, Object>();
                out.put("credited", false);
                out.put("status", "pending");
                out.put("message", "Payment is still pending.");
                return ResponseEntity.ok(ApiResponse.ok(out));
            }
            throw e;
        }
        var result = verifyResult(verified, ref);
        var status = String.valueOf(result.get("status"));

        boolean credited = false;
        if ("success".equals(status)) {
            credited = creditFromVerified(user.getId(), ref, verified);
        }

        log.info("[ZentraPay][status] DONE — ref='{}' status='{}' credited={}", ref, status, credited);
        var out = new LinkedHashMap<String, Object>();
        out.put("credited", credited);
        out.put("status", status);
        out.put("message", "success".equals(status)
                ? (credited ? "Payment verified. Your wallet has been credited."
                            : "Payment was already processed.")
                : "failed".equals(status)
                        ? "Payment failed or was cancelled. No money left your account."
                        : "Payment is still pending.");
        if (verified.get("data") instanceof Map<?, ?> vdm
                && ((Map<String, Object>) vdm).get("amount") != null)
            out.put("amount", ((Map<String, Object>) vdm).get("amount"));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/zentrapay   (covered by POST /api/webhooks/** permitAll)
    // Header: x-zentrapay-signature = hex(HMAC_SHA256(rawBody, webhookSecret))
    // Payload: { "event": "charge.success", "data": { "reference": "DEP_...", ... } }
    // The reference is only used for routing here — nothing is credited until
    // the signature passes AND the server-to-server verify below says SUCCESS.
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/webhooks/zentrapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-zentrapay-signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[ZentraPay][Webhook] HIT — remote='{}'", request.getRemoteAddr());

        // 1. Raw body (before any parsing)
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[ZentraPay][Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        // 2. Signature present?
        if (signature == null || signature.isBlank()) {
            log.warn("[ZentraPay][Webhook] REJECTED — missing x-zentrapay-signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        // 3. Resolve the signing secret for this payment: the reference tells
        // us whose branch it belongs to, so a branch that set its own
        // ZentraPay keys verifies with that webhook secret; everyone else
        // uses the platform secret, then the env secret. When the reference
        // cannot be read from the payload, fall back to the env secret.
        UUID refUserId = null;
        String refFromPayload = null;
        try {
            @SuppressWarnings("unchecked")
            var peek = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
            refFromPayload = extractReference(peek);
            if (refFromPayload != null) {
                refUserId = parseUserIdFromReference(refFromPayload);
            }
        } catch (Exception ignored) { }
        var key = refUserId != null ? webhookSecretForUserId(refUserId) : webhookSecret;

        if (!verifySignature(rawBody, signature.trim(), key)) {
            log.warn("[ZentraPay][Webhook] REJECTED — HMAC mismatch (wrong webhook secret?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        // 4. Parse + dispatch
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("event"));
            if ("refund.processed".equals(eventType)) {
                // Guide v2 Step 8: the gateway confirms a refund here.
                // Refunds never touch the player wallet (the SA adjusts it
                // by hand if needed) — log it and ack so it isn't retried.
                var rref = refFromPayload != null ? refFromPayload : extractReference(event);
                log.info("[ZentraPay][Webhook] refund.processed ref='{}' — no wallet change", rref);
                return ResponseEntity.ok("OK-REFUND");
            }
            if (!"charge.success".equals(eventType)) {
                log.info("[ZentraPay][Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            var ref = refFromPayload != null ? refFromPayload : extractReference(event);
            if (ref == null || ref.isBlank()) {
                log.error("[ZentraPay][Webhook] charge.success without reference");
                return ResponseEntity.status(400).body("Missing reference");
            }

            var userId = parseUserIdFromReference(ref);
            if (userId == null) {
                // Not one of ours (or malformed). 200 so it isn't treated as a delivery failure.
                log.error("[ZentraPay][Webhook] UNRESOLVABLE userId from ref='{}'", ref);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // Never credit from the webhook body — confirm with ZentraPay server-to-server.
            var verified = zentraPayVerify(ref, secretKeyForUserId(userId));
            var result = verifyResult(verified, ref);
            var status = String.valueOf(result.get("status"));
            if (!"success".equals(status)) {
                log.warn("[ZentraPay][Webhook] Verify says status='{}' for ref='{}' — NOT crediting", status, ref);
                return ResponseEntity.ok("OK-NOT-SUCCESS");
            }

            creditFromVerified(userId, ref, verified);

        } catch (ApiException e) {
            log.error("[ZentraPay][Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[ZentraPay][Webhook] Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[ZentraPay][Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Diagnostics + key reveal (v186) ────────────────────────────────────
    // Diagnostics: what key is actually in effect (source + last4 only,
    // NEVER the value), whether this server can reach the ZentraPay API
    // right now, whether the key is accepted (verify on a throwaway
    // reference — 401 = bad key, 404 = key good, no money moves), and the
    // last init failure on this instance. Super-admin only, own branch.
    @GetMapping("/api/super-admin/gateway/zentrapay/diagnostics")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnostics(
            @AuthenticationPrincipal User actor) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN)
            throw ApiException.forbidden("Only a super admin can run ZentraPay diagnostics for their branch.");
        var out = new LinkedHashMap<String, Object>();
        var branchStatus = gatewayService.zentraPayKeyStatus(actor.getId());
        var platformStatus = gatewayService.platformZentraPayKeyStatus();
        String keySource; String keyLast4 = null;
        if (gatewayService.branchZentraPaySecretKey(actor.getId()).isPresent()) {
            keySource = "branch"; keyLast4 = (String) branchStatus.get("secretKeyLast4");
        } else if (gatewayService.platformZentraPaySecretKey().isPresent()) {
            keySource = "platform"; keyLast4 = (String) platformStatus.get("secretKeyLast4");
        } else if (secretKey != null && !secretKey.isBlank()) {
            keySource = "env"; keyLast4 = last4(secretKey);
        } else { keySource = "none"; }
        String whSource; String whLast4 = null;
        if (gatewayService.branchZentraPayWebhookSecret(actor.getId()).isPresent()) {
            whSource = "branch"; whLast4 = (String) branchStatus.get("webhookSecretLast4");
        } else if (gatewayService.platformZentraPayWebhookSecret().isPresent()) {
            whSource = "platform"; whLast4 = (String) platformStatus.get("webhookSecretLast4");
        } else if (webhookSecret != null && !webhookSecret.isBlank()) {
            whSource = "env"; whLast4 = last4(webhookSecret);
        } else { whSource = "none"; }
        out.put("keySource", keySource);
        out.put("keyLast4", keyLast4);
        out.put("webhookSecretSource", whSource);
        out.put("webhookSecretLast4", whLast4);
        out.put("baseUrl", baseUrl);
        out.put("health", probeHealth());
        out.put("verify", probeVerify(actor));
        out.put("lastInitFailure", LAST_INIT_FAILURE.get());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // Saved branch keys, decrypted, for the eye toggle in the SA Gateways
    // tab. Super-admin only, own branch, audit-logged. The values are
    // returned ONLY here — never in the key-status or diagnostics calls.
    @GetMapping("/api/super-admin/gateway/zentrapay-keys/reveal")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revealKeys(
            @AuthenticationPrincipal User actor) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN)
            throw ApiException.forbidden("Only a super admin can view their branch ZentraPay keys.");
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.revealZentraPayKeys(actor)));
    }

    // ─── Refunds (v187, guide §9) ──────────────────────────────────────────
    // Super-admin only, own branch key, audit-logged. Refunds a SUCCESS
    // ZentraPay charge back to the customer's MoMo (partial refunds allowed).
    // ZentraPay enforces amount ≤ original (400 if too large) and the
    // reference must exist (404 otherwise). This does NOT touch the player's
    // wallet — if the credited amount was already spent, the SA adjusts the
    // wallet by hand (owner Adjust balance) after refunding here.
    @PostMapping("/api/super-admin/gateway/zentrapay/refund")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refund(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, Object> req) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN)
            throw ApiException.forbidden("Only a super admin can refund a ZentraPay payment.");
        var reference = req == null || req.get("reference") == null ? "" : String.valueOf(req.get("reference")).trim();
        if (reference.isBlank())
            throw ApiException.badRequest("Enter the ZentraPay reference to refund (from the ZentraPay dashboard).");
        BigDecimal amount;
        try {
            amount = req.get("amount") == null ? null : new BigDecimal(String.valueOf(req.get("amount")).trim());
        } catch (Exception e) {
            throw ApiException.badRequest("Enter a valid refund amount.");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Refund amount must be more than 0.");
        var reason = req.get("reason") == null || String.valueOf(req.get("reason")).isBlank()
                ? "Refund requested by super admin" : String.valueOf(req.get("reason")).trim();
        var key = secretKeyFor(actor);
        var body = new LinkedHashMap<String, Object>();
        body.put("reference", reference);
        body.put("amount", amount);
        body.put("reason", reason);
        // One stable idempotency key per (reference, amount) attempt — a
        // second refund of the same reference for a different amount must
        // not replay the first one.
        String idem = "refund_" + reference + "_" + amount.stripTrailingZeros().toPlainString();
        var result = callZentraPay(HttpMethod.POST, "/v1/refunds", body, true, "refund", key, idem);
        gatewayService.logZentraPayRefund(actor, reference, amount, reason);
        var out = new LinkedHashMap<String, Object>();
        out.put("reference", reference);
        out.put("amount", amount);
        out.put("reason", reason);
        out.put("zentrapay", result);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    private Map<String, Object> probeHealth() {
        var out = new LinkedHashMap<String, Object>();
        long start = System.currentTimeMillis();
        try {
            var res = webClientBuilder.build().get().uri(baseUrl + "/health")
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange().timeout(Duration.ofSeconds(5)).block();
            int status = res != null ? res.statusCode().value() : 0;
            String body = "";
            if (res != null) {
                try { body = res.bodyToMono(String.class).defaultIfEmpty("").block(); } catch (Exception ignored) { }
            }
            out.put("reachable", true);
            out.put("status", status);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", null);
            out.put("mode", extractMode(body));
        } catch (Exception e) {
            log.warn("[ZentraPay][diagnostics] health probe failed — {}", e.getMessage());
            out.put("reachable", false);
            out.put("status", null);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", upstreamReason(e));
            out.put("mode", null);
        }
        return out;
    }

    // The health endpoint reports a "mode" field ("mock" on the working host
    // as of 20 Nov 2026). Surfaced in diagnostics so the super admin can see
    // it — a mock-mode gateway must be confirmed real-money before live use.
    @SuppressWarnings("unchecked")
    private String extractMode(String body) {
        try {
            var parsed = (Map<String, Object>) objectMapper.readValue(body, Map.class);
            Object mode = parsed.get("mode");
            return mode == null ? null : String.valueOf(mode);
        } catch (Exception ignored) { return null; }
    }

    private Map<String, Object> probeVerify(User actor) {
        var out = new LinkedHashMap<String, Object>();
        var key = secretKeyFor(actor);
        if (key == null || key.isBlank()) {
            out.put("status", null);
            out.put("verdict", "no key configured");
            out.put("message", null);
            return out;
        }
        String ref = "ZP_DIAG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            var res = webClientBuilder.build().get().uri(baseUrl + "/v1/charges/verify/" + ref)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange().timeout(Duration.ofSeconds(10)).block();
            int status = res != null ? res.statusCode().value() : 0;
            String body = "";
            if (res != null) {
                try { body = res.bodyToMono(String.class).defaultIfEmpty("").block(); } catch (Exception ignored) { }
            }
            out.put("status", status);
            if (status == 401) { out.put("verdict", "key rejected"); out.put("message", extractErrorMessage(body)); }
            else if (status == 404) { out.put("verdict", "key accepted"); out.put("message", "Reference not found is expected — the key itself works."); }
            else if (status == 200) { out.put("verdict", "key accepted"); out.put("message", extractErrorMessage(body)); }
            else { out.put("verdict", "upstream HTTP " + status); out.put("message", extractErrorMessage(body)); }
        } catch (Exception e) {
            log.warn("[ZentraPay][diagnostics] verify probe failed — {}", e.getMessage());
            out.put("status", null);
            out.put("verdict", "unreachable");
            out.put("message", upstreamReason(e));
        }
        return out;
    }

    // ─── ZentraPay key resolution ─────────────────────────────────────────────
    // A super admin can set their OWN ZentraPay secret key + webhook secret
    // for their branch (GatewayService.setZentraPayKeys). When set, deposits
    // from users on that branch run on the super admin's ZentraPay account;
    // otherwise the platform keys (owner-set, else env) are used. Mirrors
    // the RushPay branch-key pattern.
    private String secretKeyFor(User user) {
        if (user != null) {
            try {
                var saId = gatewayService.resolveBranchSuperAdmin(user);
                if (saId.isPresent()) {
                    var branch = gatewayService.branchZentraPaySecretKey(saId.get());
                    if (branch.isPresent()) return branch.get();
                }
            } catch (Exception e) {
                log.warn("ZentraPay key resolution fell back to platform key: {}", e.getMessage());
            }
        }
        var platform = gatewayService.platformZentraPaySecretKey();
        if (platform.isPresent()) return platform.get();
        return secretKey;
    }

    private String secretKeyForUserId(UUID userId) {
        try {
            var saId = gatewayService.resolveBranchSuperAdminId(userId);
            if (saId.isPresent()) {
                var branch = gatewayService.branchZentraPaySecretKey(saId.get());
                if (branch.isPresent()) return branch.get();
            }
        } catch (Exception e) {
            log.warn("ZentraPay webhook key resolution fell back to platform key: {}", e.getMessage());
        }
        var platform = gatewayService.platformZentraPaySecretKey();
        if (platform.isPresent()) return platform.get();
        return secretKey;
    }

    private String webhookSecretForUserId(UUID userId) {
        try {
            var saId = gatewayService.resolveBranchSuperAdminId(userId);
            if (saId.isPresent()) {
                var branch = gatewayService.branchZentraPayWebhookSecret(saId.get());
                if (branch.isPresent()) return branch.get();
            }
        } catch (Exception e) {
            log.warn("ZentraPay webhook secret resolution fell back to platform secret: {}", e.getMessage());
        }
        var platform = gatewayService.platformZentraPayWebhookSecret();
        if (platform.isPresent()) return platform.get();
        return webhookSecret;
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    /**
     * Normalises a ZentraPay verify response to our own status shape.
     * Verify data: { reference, status: "SUCCESS"|"PENDING"|"FAILED",
     * amount, fee, net_amount, created_at }. Anything unrecognised stays
     * pending — never defaults to success.
     */
    private Map<String, Object> verifyResult(Map<String, Object> response, String ref) {
        @SuppressWarnings("unchecked")
        var data = response.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : null;
        var raw = data != null && data.get("status") != null ? String.valueOf(data.get("status")) : null;
        String status;
        if ("SUCCESS".equalsIgnoreCase(raw)) {
            status = "success";
        } else if ("FAILED".equalsIgnoreCase(raw)) {
            status = "failed";
        } else {
            status = "pending";
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("reference", ref);
        out.put("status", status);
        return out;
    }

    private boolean creditFromVerified(UUID userId, String ref, Map<String, Object> verified) {
        @SuppressWarnings("unchecked")
        var data = verified.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : null;
        if (data == null || data.get("amount") == null) {
            log.error("[ZentraPay][credit] Verify response missing data/amount ref='{}'", ref);
            throw ApiException.badRequest("Invalid verify response from ZentraPay.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(data.get("amount"))).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("[ZentraPay][credit] Unparseable amount='{}' ref='{}'", data.get("amount"), ref);
            throw ApiException.badRequest("Invalid amount from ZentraPay.");
        }
        if (amount.signum() <= 0) {
            log.error("[ZentraPay][credit] Non-positive amount={} ref='{}'", amount, ref);
            return false;
        }

        // Full requested amount is credited; 7UP absorbs the ZentraPay fee
        // (fee/net_amount in the verify response are ledger info only).
        return handleDeposit(userId, ref, amount);
    }

    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("[ZentraPay][handleDeposit] START — userId='{}' amountGHS={} ref='{}'", userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "zentrapay", "channel", "mobile_money", "reference", ref));
            log.info("[ZentraPay][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.info("[ZentraPay][handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return false;
            }
            log.error("[ZentraPay][handleDeposit] credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[ZentraPay][handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}",
                    userId, ex.getMessage(), ex);
        }
        return true;
    }

    // ─── ZentraPay API calls ────────────────────────────────────────────────────

    private Map<String, Object> zentraPayVerify(String reference, String key) {
        if (!REF_PATTERN.matcher(reference).matches())
            throw ApiException.badRequest("Invalid reference.");
        return callZentraPay(HttpMethod.GET, "/v1/charges/verify/" + reference, null, true, "verify", key, null);
    }

    private static class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("ZentraPay returned " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callZentraPay(HttpMethod method, String path, Object body,
                                              boolean auth, String tag, String key, String idempotencyKey) {
        if (auth && (key == null || key.isBlank())) {
            throw ApiException.badRequest("ZentraPay is not set up for this branch yet — save your ZentraPay secret key in the Gateways tab, or ask the platform owner to set the platform key.");
        }
        var spec = webClientBuilder.build()
                .method(method)
                .uri(baseUrl + path)
                .accept(MediaType.APPLICATION_JSON);

        if (auth) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + key);
        if (idempotencyKey != null && !idempotencyKey.isBlank())
            spec = spec.header("Idempotency-Key", idempotencyKey);
        var withBody = body != null
                ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                : spec;

        var mono = withBody.retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> {
                            log.error("[ZentraPay][{}] HTTP error — path='{}' status={} body='{}'",
                                    tag, path, r.statusCode().value(), b);
                            return (Throwable) new UpstreamException(r.statusCode().value(), b);
                        }))
                .bodyToMono(Map.class)
                .timeout(TIMEOUT);

        // Retry only idempotent GETs, and only on network/timeout/5xx. Never retry POSTs.
        if (method == HttpMethod.GET) {
            mono = mono.retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                    .filter(ex -> ex instanceof TimeoutException
                            || ex instanceof WebClientRequestException
                            || (ex instanceof UpstreamException u && u.status == 429)
                            || (ex instanceof UpstreamException u && u.status >= 500))
                    .onRetryExhaustedThrow((s, sig) -> sig.failure()));
        }

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) mono.block();
        } catch (UpstreamException e) {
            if (e.status >= 400 && e.status < 500) throw ApiException.badRequest(extractErrorMessage(e.body));
            var detail = extractErrorMessage(e.body);
            throw new ApiException("ZentraPay is currently unavailable (upstream HTTP " + e.status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail) + "). Please try again.", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("[ZentraPay][{}] call failed — {}", tag, e.getMessage(), e);
            throw upstreamUnavailable(e);
        }

        if (result == null) throw new ApiException("ZentraPay returned an empty response. Please try again.", HttpStatus.BAD_GATEWAY);
        return result;
    }

    /** ZentraPay errors look like { "status": false, "message": "..." }. */
    @SuppressWarnings("unchecked")
    private String extractErrorMessage(String rawBody) {
        try {
            var parsed = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
            if (parsed.get("message") != null) return parsed.get("message").toString();
            if (parsed.get("detail") != null) return parsed.get("detail").toString();
        } catch (Exception ignored) { }
        if (rawBody != null && !rawBody.isBlank() && rawBody.length() < 200) return rawBody.trim();
        return "Payment request was rejected.";
    }

    // ─── Reference helpers ─────────────────────────────────────────────────────

    private String buildReference(UUID userId) {
        var rand = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "DEP_" + userId.toString().replace("-", "") + "_" + rand;
    }

    private UUID parseUserIdFromReference(String ref) {
        Matcher m = REF_PATTERN.matcher(ref);
        if (!m.matches()) return null;
        var h = m.group(1);
        try {
            return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                    + "-" + h.substring(16, 20) + "-" + h.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void assertReferenceBelongsTo(User user, String reference) {
        var owner = parseUserIdFromReference(reference);
        if (owner == null || !owner.equals(user.getId()))
            throw ApiException.badRequest("Unknown reference.");
    }

    /**
     * Finds our DEP_ reference in a ZentraPay webhook payload. The docs
     * show the event name but no sample body, so look in the usual places:
     * data.reference, top-level reference, data.charge_reference.
     */
    @SuppressWarnings("unchecked")
    private String extractReference(Map<String, Object> payload) {
        if (payload == null) return null;
        if (payload.get("data") instanceof Map<?, ?> dm) {
            var data = (Map<String, Object>) dm;
            if (data.get("reference") != null && !data.get("reference").toString().isBlank())
                return data.get("reference").toString().trim();
            if (data.get("charge_reference") != null && !data.get("charge_reference").toString().isBlank())
                return data.get("charge_reference").toString().trim();
        }
        if (payload.get("reference") != null && !payload.get("reference").toString().isBlank())
            return payload.get("reference").toString().trim();
        return null;
    }

    // ─── Validation helpers ────────────────────────────────────────────────────

    private BigDecimal extractValidAmount(Map<String, Object> req) {
        var raw = req.get("amount");
        if (raw == null) throw ApiException.badRequest("amount is required.");
        BigDecimal amount;
        try { amount = new BigDecimal(raw.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("amount must be a valid number."); }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        return amount;
    }

    private String normalizeGhanaPhone(String raw) {
        var digits = raw.replaceAll("[\\s\\-]", "");
        if (digits.startsWith("+233"))                              digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest("Invalid Ghana phone. Use 0XXXXXXXXX or +233XXXXXXXXX.");
        return digits;
    }

    private void warnIfUnknownPrefix(String phone) {
        var p = phone.substring(0, 3);
        if (!MTN_GH_PREFIXES.contains(p) && !ATL_GH_PREFIXES.contains(p) && !VOD_GH_PREFIXES.contains(p))
            log.warn("[ZentraPay] phone prefix='{}' not in known Ghana MoMo ranges", p);
    }

    /** Maps a Ghana prefix to ZentraPay's network enum; null = let ZentraPay auto-detect. */
    private String networkForPrefix(String prefix) {
        if (MTN_GH_PREFIXES.contains(prefix)) return "MTN";
        if (VOD_GH_PREFIXES.contains(prefix)) return "TELECEL";
        if (ATL_GH_PREFIXES.contains(prefix)) return "AT";
        return null;
    }

    /** Accepts MTN/TELECEL/AT (and common aliases); null when unrecognised. */
    private String normalizeNetwork(String raw) {
        var v = raw.trim().toUpperCase();
        if (v.equals("MTN")) return "MTN";
        if (v.equals("TELECEL") || v.equals("VODAFONE") || v.equals("VOD")) return "TELECEL";
        if (v.equals("AT") || v.equals("AIRTELTIGO") || v.equals("AIRTEL") || v.equals("TIGO")) return "AT";
        return null;
    }

    // ─── Signature ─────────────────────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature, String key) {
        if (key == null || key.isBlank()) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            // constant-time compare
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[ZentraPay][verifySignature] HMAC error", e);
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
