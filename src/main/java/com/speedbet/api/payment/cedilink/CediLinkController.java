package com.speedbet.api.payment.cedilink;

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
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CediLink (https://cedilinkweb-production.up.railway.app) mobile-money
 * deposit integration — Ghana MoMo only (GHS). Replaces RushPay as the
 * platform/general Ghana gateway (v215); the site owner sets the platform
 * CediLink keys and a super admin can set their own branch keys.
 *
 * Flow (direct MoMo prompt — no hosted checkout, no browser widget):
 *  1. POST /api/wallet/deposit/cedilink/init {amount, phone, network?}
 *     -> verify-number gates the number (only verified:true approves a
 *        prompt; no_account means do not prompt), then POST
 *        /v1/payment_intents creates the intent (amount in PESEWAS),
 *        then POST /v1/checkout/{id}/momo?cs={client_secret} sends the
 *        USSD/STK prompt straight to the customer's handset.
 *        Returns {reference, intentId, status: "PENDING", accountName,
 *        total, fee}. The reference is OURS (DEP_<userId>_<random>) so
 *        the userId is recoverable from every later call and webhook.
 *  2. Customer approves on the phone with their MoMo PIN; CediLink POSTs
 *     the success event to /api/webhooks/cedilink.
 *  3. GET  /api/wallet/deposit/cedilink/status?ref=...&intentId=... ->
 *     frontend poll; server-to-server GET /v1/payment_intents/{id},
 *     credits on succeeded (idempotent — duplicate references 409 and
 *     are swallowed). If next_action.type is submit_otp the poll returns
 *     requires_otp and the frontend posts the code to /validate.
 *
 * Why server-to-server: CediLink's intent API is a plain Bearer-auth
 * server API — no browser-scoped widget session — so the secret key
 * never leaves this backend. Do not move the intent call to the browser.
 *
 * Security model (mirrors ZentraPay):
 *  - Webhook identity = HMAC-SHA256 over "t.<raw body>", keyed with the
 *    webhook secret (header X-CediLink-Signature: t=<unix>,v1=<hex>).
 *    The secret is resolved per payment from the reference: branch key,
 *    else platform key, else env.
 *  - Even after the signature passes, we NEVER trust the payload: we call
 *    CediLink's retrieve API and credit only the amount/status it returns
 *    server-to-server.
 *  - The reference embeds the userId, and the status endpoint asserts it
 *    belongs to the authenticated user, so one user can't trigger or
 *    inspect another user's deposit.
 *
 * Money rule: the wallet is credited the FULL requested amount; the
 * customer pays amount + 2% (CediLink's fee, on top). The verify-number
 * response carries the total the customer will pay; the credit uses the
 * intent amount (minor units / 100).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CediLinkController {

    // DEP_<32 hex userId without dashes>_<12 hex random> (same as ZentraPay)
    private static final Pattern REF_PATTERN = Pattern.compile("^DEP_([0-9a-f]{32})_[0-9a-f]{12}$");

    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

    // CediLink takes 2% from the customer on top of the requested amount.
    private static final BigDecimal FEE_RATE = new BigDecimal("0.02");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    // Verify (status check) gets a short timeout so a slow CediLink verify
    // fails fast instead of blocking the poll. The frontend poll IS the
    // retry — it re-checks almost immediately. The init POST keeps the
    // full 15s and is never retried.
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(2);

    private final WalletService    walletService;
    private final ReferralService  referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper     objectMapper;
    private final GatewayService   gatewayService;

    @Value("${app.cedilink.secret-key:}")
    private String secretKey;
    @Value("${app.cedilink.webhook-secret:}")
    private String webhookSecret;
    // CediLink production base (Postman collection base_url).
    private final String baseUrl = "https://cedilinkweb-production.up.railway.app/v1";
    @Value("${app.platform.min-deposit-amount:1}")
    private BigDecimal minDeposit;

    // Charge email for EVERY deposit (owner's order, 21 Nov 2026 for
    // ZentraPay, same reason here): one known-good address for all
    // intents, so a bad/blank player email can never get a charge
    // rejected.
    private static final String CHARGE_EMAIL = "kbaah299@gmail.com";

    @PostConstruct
    public void logCediLinkHealthAtStartup() {
        try {
            var h = probeHealth();
            log.info("[CediLink][startup] baseUrl='{}' reachable={} status={} error={}",
                    baseUrl, h.get("reachable"), h.get("status"), h.get("error"));
        } catch (Exception e) {
            log.warn("[CediLink][startup] health check failed — {}", e.getMessage());
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
        try { return URI.create(baseUrl).getHost(); } catch (Exception e) { return "the CediLink API"; }
    }

    /** Short plain-language reason for an upstream failure (no stack traces leave the server). */
    private String upstreamReason(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException)
                return "Could not reach CediLink — could not resolve " + apiHost();
            if (t instanceof ConnectException)
                return "Could not reach CediLink — connection refused";
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException)
                return "CediLink did not answer in time (15s timeout)";
            if (t instanceof SSLException)
                return "Could not reach CediLink — secure connection failed";
            t = t.getCause();
        }
        if (e != null && (e.getClass().getSimpleName().contains("Decoding") || e.getClass().getSimpleName().contains("Json")))
            return "CediLink returned an unexpected response";
        return "CediLink is currently unavailable";
    }

    private ApiException upstreamUnavailable(Throwable e) {
        return new ApiException(upstreamReason(e) + ". Please try again.", HttpStatus.BAD_GATEWAY);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1 — Direct MoMo intent + prompt (verify-number gate first)
    // Body: { "amount": 50, "phone": "0241234567", "network": "MTN" (optional) }
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/cedilink/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> init(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        gatewayService.requireDepositsEnabled(user);
        log.info("[CediLink][init] START — userId='{}'", user.getId());

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
        // unknown prefix -> omit and let CediLink auto-detect.
        String network = null;
        var rawNet = req.get("network");
        if (rawNet != null && !rawNet.toString().isBlank()) {
            network = normalizeNetwork(rawNet.toString().trim());
        }
        if (network == null) {
            network = networkForPrefix(phone.substring(0, 3));
        }

        var reference = buildReference(user.getId());
        var key = secretKeyFor(user);
        // Amount in minor units (pesewas) — CediLink takes integers.
        long amountMinor = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        // Customer pays requested + 2% (CediLink fee, on top).
        var fee = amount.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        var total = amount.add(fee).setScale(2, RoundingMode.HALF_UP);

        // Gate 1 — verify-number: only verified:true approves sending the
        // prompt. no_account means do not prompt; a 502 means the check
        // could not run — retry later. Runs BEFORE any intent is created
        // so a bad number never leaves a wasted intent behind.
        String accountName = null;
        try {
            var verifyBody = new LinkedHashMap<String, Object>();
            verifyBody.put("phone", phone);
            if (network != null) verifyBody.put("network", network);
            verifyBody.put("amount_minor", amountMinor);
            verifyBody.put("currency", "GHS");
            var vres = callCediLink(HttpMethod.POST, "/verify-number", verifyBody, true, "verify-number", key, null);
            Boolean verified = null;
            for (var k : new String[]{"verified", "is_verified", "valid", "is_valid"}) {
                if (vres.get(k) instanceof Boolean b) { verified = b; break; }
            }
            if (verified != null && !verified) {
                recordInitFailure("no-account", "Number has no MoMo account on this network", maskPhone(phone));
                throw ApiException.badRequest("This number has no MoMo account on "
                        + (network != null ? network : "this network") + ". Check the number and try again.");
            }
            if (vres.get("account_name") != null) accountName = String.valueOf(vres.get("account_name"));
            else if (vres.get("accountName") != null) accountName = String.valueOf(vres.get("accountName"));
            // Prefer the gateway's own total when it returns one.
            var gwTotal = extractMinorAsGhs(vres, "total", "total_minor", "total_amount", "amount_to_pay", "payable_minor");
            if (gwTotal != null) total = gwTotal;
        } catch (ApiException e) {
            // A 502 from verify-number means the check could not run.
            if (e.getStatus() == HttpStatus.BAD_GATEWAY) {
                recordInitFailure("verify-unavailable", e.getMessage(), maskPhone(phone));
                throw ApiException.badRequest("CediLink could not verify this number right now. Please try again in a moment.");
            }
            throw e;
        }

        // Gate 2 — create the payment intent (201, no prompt sent yet).
        var intentBody = new LinkedHashMap<String, Object>();
        intentBody.put("amount", amountMinor);
        intentBody.put("currency", "GHS");
        intentBody.put("method", "mobile_money");
        if (network != null) intentBody.put("network", network);
        intentBody.put("customer", Map.of("phone", phone, "email", CHARGE_EMAIL));
        intentBody.put("reference", reference);
        intentBody.put("description", "7UP deposit " + reference);
        intentBody.put("metadata", Map.of("user_id", user.getId().toString(), "reference", reference));

        Map<String, Object> created;
        try {
            created = callCediLink(HttpMethod.POST, "/payment_intents", intentBody, true, "create-intent", key, reference);
        } catch (ApiException e) {
            if (e.getStatus().value() >= 500)
                recordInitFailure("upstream", e.getMessage(), maskPhone(phone));
            throw e;
        }
        var intentId = created.get("id") != null ? String.valueOf(created.get("id")) : null;
        var clientSecret = created.get("client_secret") != null ? String.valueOf(created.get("client_secret")) : null;
        if (intentId == null || intentId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            recordInitFailure("bad-intent", "Create intent returned no id/client_secret", maskPhone(phone));
            throw new ApiException("CediLink did not start the payment. Please try again.", HttpStatus.BAD_GATEWAY);
        }
        log.info("[CediLink][init] intent created — userId='{}' ref='{}' intentId='{}' amountGHS={} phone='{}' network='{}'",
                user.getId(), reference, intentId, amount, maskPhone(phone), network);

        // Gate 3 — start the MoMo collection (sends the prompt). The
        // checkout call takes no Bearer auth — the client secret in the
        // query string is the credential. 409 already_submitted means a
        // prompt is already waiting: poll instead, never a second prompt.
        var momoBody = new LinkedHashMap<String, Object>();
        momoBody.put("phone", phone);
        if (network != null) momoBody.put("network", network);
        try {
            callCediLinkNoAuth(HttpMethod.POST,
                    "/checkout/" + intentId + "/momo?cs=" + clientSecret,
                    momoBody, "start-momo");
        } catch (ApiException e) {
            // 409 already_submitted is not a failure — a prompt is already
            // waiting, so the frontend just polls.
            if (e.getStatus() == HttpStatus.CONFLICT) {
                log.info("[CediLink][init] prompt already waiting for intentId='{}' — polling instead", intentId);
            } else {
                recordInitFailure("prompt-failed", e.getMessage(), maskPhone(phone));
                throw e;
            }
        }

        // Background verifier: a PENDING intent is checked server-side
        // and credited on succeeded even if the user leaves the page or
        // the webhook never arrives. Failed intents are never registered.
        PENDING.put(reference, new PendingIntent(user.getId(), intentId, clientSecret, Instant.now()));
        log.info("[CediLink][init] DONE — userId='{}' ref='{}' intentId='{}' status='PENDING'", user.getId(), reference, intentId);

        var out = new LinkedHashMap<String, Object>();
        out.put("reference", reference);
        out.put("intentId", intentId);
        out.put("status", "PENDING");
        out.put("amount", amount);
        out.put("fee", fee);
        out.put("total", total);
        if (accountName != null) out.put("accountName", accountName);
        if (network != null) out.put("network", network);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ─── Background verifier ────────────────────────────────────────────────
    // Same shape as ZentraPay's: once init returns PENDING the intent is
    // registered here; every 0.5s the verifier asks CediLink itself and
    // credits on succeeded (same idempotent creditFromVerified the poll +
    // webhook use — a duplicate 409 is swallowed, so it can never
    // double-credit). Failed drops out, still-pending stays until the
    // 5-minute cap (same window as the frontend poll), then drops.
    // In-memory only: a restart loses the pending list, and those intents
    // fall back to the webhook + frontend poll. Never throws.
    private record PendingIntent(UUID userId, String intentId, String clientSecret, Instant createdAt) {}

    private static final ConcurrentHashMap<String, PendingIntent> PENDING = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 500)
    public void verifyPendingIntents() {
        if (PENDING.isEmpty()) return;
        var now = Instant.now();
        for (var entry : PENDING.entrySet()) {
            var ref = entry.getKey();
            var pc = entry.getValue();
            if (Duration.between(pc.createdAt(), now).toMinutes() >= 5) {
                PENDING.remove(ref);
                log.info("[CediLink][verify-bg] Giving up on ref='{}' after 5 min (still pending)", ref);
                continue;
            }
            try {
                var retrieved = cediLinkRetrieve(pc.intentId(), secretKeyForUserId(pc.userId()));
                var result = verifyResult(retrieved, ref);
                var status = String.valueOf(result.get("status"));
                if ("success".equals(status)) {
                    boolean credited;
                    try {
                        credited = creditFromVerified(pc.userId(), ref, retrieved);
                    } catch (ApiException ce) {
                        log.error("[CediLink][verify-bg] credit FAILED for ref='{}' — {} — retrying until the 5-min cap", ref, ce.getMessage());
                        continue;
                    }
                    PENDING.remove(ref);
                    log.info("[CediLink][verify-bg] ref='{}' {} in background", ref, credited ? "credited" : "already credited");
                } else if ("failed".equals(status)) {
                    PENDING.remove(ref);
                    log.info("[CediLink][verify-bg] ref='{}' failed — dropping", ref);
                }
            } catch (Exception e) {
                log.warn("[CediLink][verify-bg] ref='{}' check failed — {} — retrying", ref, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 2 — Status poll (frontend, 0.25s, up to 5 min)
    // GET /api/wallet/deposit/cedilink/status?ref=DEP_...&intentId=pi_...
    // ═══════════════════════════════════════════════════════════════════════════
    @GetMapping("/api/wallet/deposit/cedilink/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String ref,
            @RequestParam(value = "intentId", required = false) String intentId) {

        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest("ref is required.");
        }
        assertReferenceBelongsTo(user, ref);
        log.info("[CediLink][status] userId='{}' ref='{}' intentId='{}'", user.getId(), ref, intentId);

        // The intent id comes from the frontend (init returned it); fall
        // back to the background-verifier map when the frontend has none
        // (e.g. an older client that only kept the reference).
        String iid = intentId;
        if ((iid == null || iid.isBlank()) && PENDING.get(ref) != null) {
            iid = PENDING.get(ref).intentId();
        }
        if (iid == null || iid.isBlank()) {
            throw ApiException.badRequest("intentId is required.");
        }

        Map<String, Object> retrieved;
        try {
            retrieved = cediLinkRetrieve(iid, secretKeyFor(user));
        } catch (ApiException e) {
            // A freshly created intent may not be visible to retrieve yet
            // (404 "not found"). Treat it as PENDING so the frontend poll
            // keeps waiting instead of 400ing on every attempt.
            if (e.getStatus() == HttpStatus.BAD_REQUEST
                    && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("not found")) {
                log.info("[CediLink][status] retrieve 404 for intentId='{}' — treating as PENDING", iid);
                var out = new LinkedHashMap<String, Object>();
                out.put("credited", false);
                out.put("status", "pending");
                out.put("message", "Payment is still pending.");
                return ResponseEntity.ok(ApiResponse.ok(out));
            }
            throw e;
        }
        // The intent must be ours: its reference has to match the ref the
        // user asked about, so one user can't inspect another's intent.
        var intentRef = extractIntentReference(retrieved);
        if (intentRef != null && !intentRef.equals(ref)) {
            throw ApiException.badRequest("Unknown reference.");
        }
        var result = verifyResult(retrieved, ref);
        var status = String.valueOf(result.get("status"));

        boolean credited = false;
        if ("success".equals(status)) {
            credited = creditFromVerified(user.getId(), ref, retrieved);
        }

        log.info("[CediLink][status] DONE — ref='{}' status='{}' credited={}", ref, status, credited);
        var out = new LinkedHashMap<String, Object>();
        out.put("credited", credited);
        out.put("status", status);
        out.put("message", "success".equals(status)
                ? (credited ? "Payment verified. Your wallet has been credited."
                            : "Payment was already processed.")
                : "failed".equals(status)
                        ? "Payment failed or was cancelled. No money left your account."
                        : "requires_otp".equals(status)
                                ? "Enter the OTP sent to your phone to approve this payment."
                                : "Payment is still pending.");
        var amt = extractIntentAmountGhs(retrieved);
        if (amt != null) out.put("amount", amt);
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 2b — OTP validation (only when the intent's next_action.type is
    // submit_otp). POST /api/wallet/deposit/cedilink/validate
    // Body: { "ref": "DEP_...", "intentId": "pi_...", "otp": "123456" }
    // A wrong code returns 402 otp_invalid from CediLink.
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/cedilink/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var ref = req == null || req.get("ref") == null ? "" : String.valueOf(req.get("ref")).trim();
        var intentId = req == null || req.get("intentId") == null ? "" : String.valueOf(req.get("intentId")).trim();
        var otp = req == null || req.get("otp") == null ? "" : String.valueOf(req.get("otp")).trim();
        if (ref.isBlank() || intentId.isBlank()) throw ApiException.badRequest("ref and intentId are required.");
        if (otp.isBlank()) throw ApiException.badRequest("Enter the OTP code.");
        assertReferenceBelongsTo(user, ref);
        log.info("[CediLink][validate] userId='{}' ref='{}' intentId='{}'", user.getId(), ref, intentId);

        var body = Map.of("otp", otp);
        Map<String, Object> result;
        try {
            result = callCediLink(HttpMethod.POST, "/payment_intents/" + intentId + "/validate",
                    body, true, "validate-otp", secretKeyFor(user), null);
        } catch (ApiException e) {
            // CediLink answers a wrong code with 402 otp_invalid.
            if (e.getStatus() == HttpStatus.PAYMENT_REQUIRED) {
                throw ApiException.badRequest("Wrong OTP code. Check the code and try again.");
            }
            throw e;
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("reference", ref);
        out.put("intentId", intentId);
        out.put("status", String.valueOf(result.get("status") != null ? result.get("status") : "pending"));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/cedilink   (covered by POST /api/webhooks/** permitAll)
    // Header: X-CediLink-Signature: t=<unix>,v1=HMAC-SHA256(secret, t + '.' + raw body)
    // The payload shape is not documented with a sample, so the intent id
    // and our reference are looked up defensively (see extractIntentId /
    // extractReference). Nothing is credited until the signature passes
    // AND the server-to-server retrieve below says succeeded.
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/webhooks/cedilink")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-CediLink-Signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[CediLink][Webhook] HIT — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[CediLink][Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("[CediLink][Webhook] REJECTED — missing X-CediLink-Signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        // Resolve the signing secret for this payment: the reference tells
        // us whose branch it belongs to, so a branch that set its own
        // CediLink keys verifies with that webhook secret; everyone else
        // uses the platform secret, then the env secret. When the
        // reference cannot be read from the payload, fall back to the env
        // secret.
        UUID refUserId = null;
        String refFromPayload = null;
        String intentIdFromPayload = null;
        try {
            @SuppressWarnings("unchecked")
            var peek = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
            refFromPayload = extractReference(peek);
            intentIdFromPayload = extractIntentId(peek);
            if (refFromPayload != null) {
                refUserId = parseUserIdFromReference(refFromPayload);
            }
        } catch (Exception ignored) { }
        var key = refUserId != null ? webhookSecretForUserId(refUserId) : webhookSecret;

        if (!verifySignature(rawBody, signature.trim(), key)) {
            log.warn("[CediLink][Webhook] REJECTED — HMAC mismatch (wrong webhook secret?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("type") != null ? String.valueOf(event.get("type"))
                    : (event.get("event") != null ? String.valueOf(event.get("event")) : "");
            var isSuccess = eventType.toLowerCase().contains("succeed")
                    || eventType.toLowerCase().contains("success")
                    || eventType.toLowerCase().contains("completed");
            if (!isSuccess) {
                log.info("[CediLink][Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            var ref = refFromPayload != null ? refFromPayload : extractReference(event);
            var intentId = intentIdFromPayload != null ? intentIdFromPayload : extractIntentId(event);
            if (intentId == null || intentId.isBlank()) {
                log.error("[CediLink][Webhook] success event without an intent id");
                return ResponseEntity.status(400).body("Missing intent id");
            }
            if (ref == null || ref.isBlank()) {
                log.error("[CediLink][Webhook] success event without a reference");
                return ResponseEntity.status(400).body("Missing reference");
            }

            var userId = parseUserIdFromReference(ref);
            if (userId == null) {
                log.error("[CediLink][Webhook] UNRESOLVABLE userId from ref='{}'", ref);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // Never credit from the webhook body — confirm with CediLink server-to-server.
            var retrieved = cediLinkRetrieve(intentId, secretKeyForUserId(userId));
            var result = verifyResult(retrieved, ref);
            var status = String.valueOf(result.get("status"));
            if (!"success".equals(status)) {
                log.warn("[CediLink][Webhook] Retrieve says status='{}' for ref='{}' — NOT crediting", status, ref);
                return ResponseEntity.ok("OK-NOT-SUCCESS");
            }

            creditFromVerified(userId, ref, retrieved);

        } catch (ApiException e) {
            log.error("[CediLink][Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[CediLink][Webhook] Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[CediLink][Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Diagnostics + key reveal ───────────────────────────────────────────
    // Diagnostics: what key is actually in effect (source + last4 only,
    // NEVER the value), whether this server can reach the CediLink API
    // right now, whether the key is accepted (GET /v1/account — 401 = bad
    // key, 200 = key good, no money moves), and the last init failure on
    // this instance. Super-admin only, own branch.
    @GetMapping("/api/super-admin/gateway/cedilink/diagnostics")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnostics(
            @AuthenticationPrincipal User actor) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN)
            throw ApiException.forbidden("Only a super admin can run CediLink diagnostics for their branch.");
        var out = new LinkedHashMap<String, Object>();
        out.put("key", gatewayService.cediLinkKeyStatus(actor.getId()));
        out.put("health", probeHealth());
        out.put("account", probeAccount(actor));
        out.put("lastInitFailure", LAST_INIT_FAILURE.get());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @GetMapping("/api/super-admin/gateway/cedilink-keys/reveal")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revealKeys(
            @AuthenticationPrincipal User actor) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN)
            throw ApiException.forbidden("Only a super admin can view their branch CediLink keys.");
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.revealCediLinkKeys(actor)));
    }

    private Map<String, Object> probeHealth() {
        var out = new LinkedHashMap<String, Object>();
        long start = System.currentTimeMillis();
        try {
            var res = webClientBuilder.build().get().uri(baseUrl + "/version")
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
            out.put("version", body != null && body.length() < 200 ? body : null);
        } catch (Exception e) {
            log.warn("[CediLink][diagnostics] health probe failed — {}", e.getMessage());
            out.put("reachable", false);
            out.put("status", null);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", upstreamReason(e));
            out.put("version", null);
        }
        return out;
    }

    private Map<String, Object> probeAccount(User actor) {
        var out = new LinkedHashMap<String, Object>();
        var key = secretKeyFor(actor);
        if (key == null || key.isBlank()) {
            out.put("status", null);
            out.put("verdict", "no key configured");
            out.put("message", null);
            return out;
        }
        try {
            var res = webClientBuilder.build().get().uri(baseUrl + "/account")
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
            else if (status == 200) {
                out.put("verdict", "key accepted");
                String mode = null;
                try {
                    @SuppressWarnings("unchecked")
                    var parsed = (Map<String, Object>) objectMapper.readValue(body, Map.class);
                    if (parsed.get("mode") != null) mode = String.valueOf(parsed.get("mode"));
                    if (parsed.get("merchant_id") != null) out.put("merchantId", String.valueOf(parsed.get("merchant_id")));
                } catch (Exception ignored) { }
                out.put("message", mode != null ? ("Key works — mode: " + mode) : "Key works.");
            }
            else { out.put("verdict", "upstream HTTP " + status); out.put("message", extractErrorMessage(body)); }
        } catch (Exception e) {
            log.warn("[CediLink][diagnostics] account probe failed — {}", e.getMessage());
            out.put("status", null);
            out.put("verdict", "unreachable");
            out.put("message", upstreamReason(e));
        }
        return out;
    }

    // ─── CediLink key resolution ──────────────────────────────────────────────
    // A super admin can set their OWN CediLink secret key + webhook secret
    // for their branch (GatewayService.setCediLinkKeys). When set, deposits
    // from users on that branch run on the super admin's CediLink account;
    // otherwise the platform keys (owner-set, else env) are used.
    private String secretKeyFor(User user) {
        if (user != null) {
            try {
                var saId = gatewayService.resolveBranchSuperAdmin(user);
                if (saId.isPresent()) {
                    var branch = gatewayService.branchCediLinkSecretKey(saId.get());
                    if (branch.isPresent()) return branch.get();
                }
            } catch (Exception e) {
                log.warn("CediLink key resolution fell back to platform key: {}", e.getMessage());
            }
        }
        var platform = gatewayService.platformCediLinkSecretKey();
        if (platform.isPresent()) return platform.get();
        return secretKey;
    }

    private String secretKeyForUserId(UUID userId) {
        try {
            var saId = gatewayService.resolveBranchSuperAdminId(userId);
            if (saId.isPresent()) {
                var branch = gatewayService.branchCediLinkSecretKey(saId.get());
                if (branch.isPresent()) return branch.get();
            }
        } catch (Exception e) {
            log.warn("CediLink webhook key resolution fell back to platform key: {}", e.getMessage());
        }
        var platform = gatewayService.platformCediLinkSecretKey();
        if (platform.isPresent()) return platform.get();
        return secretKey;
    }

    private String webhookSecretForUserId(UUID userId) {
        try {
            var saId = gatewayService.resolveBranchSuperAdminId(userId);
            if (saId.isPresent()) {
                var branch = gatewayService.branchCediLinkWebhookSecret(saId.get());
                if (branch.isPresent()) return branch.get();
            }
        } catch (Exception e) {
            log.warn("CediLink webhook secret resolution fell back to platform secret: {}", e.getMessage());
        }
        var platform = gatewayService.platformCediLinkWebhookSecret();
        if (platform.isPresent()) return platform.get();
        return webhookSecret;
    }

    // ─── Wallet crediting ─────────────────────────────────────────────────────

    /**
     * Normalises a CediLink retrieve response to our own status shape.
     * Retrieve data: { id, object, status, amount (minor), currency }.
     * Status is Stripe-like: "succeeded" is the only success. "failed",
     * "canceled"/"cancelled" are terminal failures. next_action.type
     * "submit_otp" means the customer must enter an OTP. Anything
     * unrecognised stays pending — never defaults to success.
     */
    private Map<String, Object> verifyResult(Map<String, Object> response, String ref) {
        var raw = response.get("status") != null ? String.valueOf(response.get("status")) : null;
        if (raw == null && response.get("data") instanceof Map<?, ?> dm) {
            raw = ((Map<String, Object>) dm).get("status") != null
                    ? String.valueOf(((Map<String, Object>) dm).get("status")) : null;
        }
        String status;
        if ("succeeded".equalsIgnoreCase(raw) || "success".equalsIgnoreCase(raw) || "SUCCESS".equalsIgnoreCase(raw)) {
            status = "success";
        } else if ("failed".equalsIgnoreCase(raw) || "FAILED".equalsIgnoreCase(raw)
                || "canceled".equalsIgnoreCase(raw) || "cancelled".equalsIgnoreCase(raw)) {
            status = "failed";
        } else if (requiresOtp(response)) {
            status = "requires_otp";
        } else {
            status = "pending";
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("reference", ref);
        out.put("status", status);
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean requiresOtp(Map<String, Object> response) {
        Object na = response.get("next_action");
        if (na == null && response.get("data") instanceof Map<?, ?> dm) {
            na = ((Map<String, Object>) dm).get("next_action");
        }
        if (na instanceof Map<?, ?> nam) {
            var t = ((Map<String, Object>) nam).get("type");
            return t != null && "submit_otp".equalsIgnoreCase(String.valueOf(t));
        }
        return false;
    }

    private boolean creditFromVerified(UUID userId, String ref, Map<String, Object> retrieved) {
        var amount = extractIntentAmountGhs(retrieved);
        if (amount == null) {
            log.error("[CediLink][credit] Retrieve response missing amount ref='{}'", ref);
            throw ApiException.badRequest("Invalid retrieve response from CediLink.");
        }
        if (amount.signum() <= 0) {
            log.error("[CediLink][credit] Non-positive amount={} ref='{}'", amount, ref);
            return false;
        }

        // Full requested amount is credited; the customer paid the 2%
        // fee on top, so the fee never comes out of the wallet.
        return handleDeposit(userId, ref, amount);
    }

    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("[CediLink][handleDeposit] START — userId='{}' amountGHS={} ref='{}'", userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "cedilink", "channel", "mobile_money", "reference", ref));
            log.info("[CediLink][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.info("[CediLink][handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return false;
            }
            log.error("[CediLink][handleDeposit] credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[CediLink][handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}",
                    userId, ex.getMessage(), ex);
        }
        return true;
    }

    // ─── CediLink API calls ───────────────────────────────────────────────────

    private Map<String, Object> cediLinkRetrieve(String intentId, String key) {
        if (intentId == null || intentId.isBlank())
            throw ApiException.badRequest("Invalid intent id.");
        return callCediLink(HttpMethod.GET, "/payment_intents/" + intentId, null, true, "retrieve", key, null);
    }

    private static class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("CediLink returned " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    /** Bearer-auth call (server-to-server). Idempotency-Key only on POSTs that pass one. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callCediLink(HttpMethod method, String path, Object body,
                                             boolean auth, String tag, String key, String idempotencyKey) {
        if (auth && (key == null || key.isBlank())) {
            throw ApiException.badRequest("CediLink is not set up for this branch yet — save your CediLink secret key in the Gateways tab, or ask the platform owner to set the platform key.");
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
                            log.error("[CediLink][{}] HTTP error — path='{}' status={} body='{}'",
                                    tag, path, r.statusCode().value(), b);
                            return (Throwable) new UpstreamException(r.statusCode().value(), b);
                        }))
                .bodyToMono(Map.class)
                .timeout("retrieve".equals(tag) ? VERIFY_TIMEOUT : TIMEOUT);

        // Retry only idempotent GETs, and only on network/timeout/5xx. Never retry POSTs.
        if (method == HttpMethod.GET) {
            int maxRetries = "retrieve".equals(tag) ? 0 : 2;
            mono = mono.retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(300))
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
            if (e.status == 402) {
                // CediLink answers a wrong OTP with 402 otp_invalid.
                throw new ApiException(extractErrorMessage(e.body), HttpStatus.PAYMENT_REQUIRED);
            }
            if (e.status >= 400 && e.status < 500) throw ApiException.badRequest(extractErrorMessage(e.body));
            var detail = extractErrorMessage(e.body);
            throw new ApiException("CediLink is currently unavailable (upstream HTTP " + e.status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail) + "). Please try again.", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("[CediLink][{}] call failed — {}", tag, e.getMessage(), e);
            throw upstreamUnavailable(e);
        }

        if (result == null) throw new ApiException("CediLink returned an empty response. Please try again.", HttpStatus.BAD_GATEWAY);
        return result;
    }

    /** No-auth call for the checkout endpoints (the client secret in the query string is the credential). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callCediLinkNoAuth(HttpMethod method, String path, Object body, String tag) {
        var spec = webClientBuilder.build()
                .method(method)
                .uri(baseUrl + path)
                .accept(MediaType.APPLICATION_JSON);
        var withBody = body != null
                ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                : spec;

        var mono = withBody.retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> {
                            log.error("[CediLink][{}] HTTP error — path='{}' status={} body='{}'",
                                    tag, path, r.statusCode().value(), b);
                            return (Throwable) new UpstreamException(r.statusCode().value(), b);
                        }))
                .bodyToMono(Map.class)
                .timeout(TIMEOUT);

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) mono.block();
        } catch (UpstreamException e) {
            if (e.status == 409) {
                // 409 already_submitted — a prompt is already waiting.
                throw new ApiException(extractErrorMessage(e.body), HttpStatus.CONFLICT);
            }
            if (e.status >= 400 && e.status < 500) throw ApiException.badRequest(extractErrorMessage(e.body));
            var detail = extractErrorMessage(e.body);
            throw new ApiException("CediLink is currently unavailable (upstream HTTP " + e.status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail) + "). Please try again.", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("[CediLink][{}] call failed — {}", tag, e.getMessage(), e);
            throw upstreamUnavailable(e);
        }

        if (result == null) throw new ApiException("CediLink returned an empty response. Please try again.", HttpStatus.BAD_GATEWAY);
        return result;
    }

    /** CediLink errors look like { "error": { "message": "..." } } or { "message": "..." }. */
    @SuppressWarnings("unchecked")
    private String extractErrorMessage(String rawBody) {
        try {
            var parsed = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
            if (parsed.get("error") instanceof Map<?, ?> em) {
                var m = ((Map<String, Object>) em).get("message");
                if (m != null) return m.toString();
                var code = ((Map<String, Object>) em).get("code");
                if (code != null) return code.toString();
            }
            if (parsed.get("message") != null) return parsed.get("message").toString();
            if (parsed.get("detail") != null) return parsed.get("detail").toString();
        } catch (Exception ignored) { }
        if (rawBody != null && !rawBody.isBlank() && rawBody.length() < 200) return rawBody.trim();
        return "Payment request was rejected.";
    }

    // ─── Response-shape helpers (defensive: the collection gives no samples) ─

    /** The intent's amount is in minor units (pesewas) — convert to GHS. */
    @SuppressWarnings("unchecked")
    private BigDecimal extractIntentAmountGhs(Map<String, Object> response) {
        Object raw = response.get("amount");
        if (raw == null && response.get("data") instanceof Map<?, ?> dm) {
            raw = ((Map<String, Object>) dm).get("amount");
        }
        if (raw == null) return null;
        try {
            return new BigDecimal(String.valueOf(raw))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a minor-units total from a verify-number response under any of the given keys. */
    @SuppressWarnings("unchecked")
    private BigDecimal extractMinorAsGhs(Map<String, Object> response, String... keys) {
        for (var k : keys) {
            Object raw = response.get(k);
            if (raw == null && response.get("data") instanceof Map<?, ?> dm) {
                raw = ((Map<String, Object>) dm).get(k);
            }
            if (raw != null) {
                try {
                    return new BigDecimal(String.valueOf(raw))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** Our DEP_ reference, as stored on the CediLink intent. */
    @SuppressWarnings("unchecked")
    private String extractIntentReference(Map<String, Object> response) {
        Object ref = response.get("reference");
        if (ref == null && response.get("data") instanceof Map<?, ?> dm) {
            ref = ((Map<String, Object>) dm).get("reference");
        }
        if (ref == null && response.get("metadata") instanceof Map<?, ?> mm) {
            ref = ((Map<String, Object>) mm).get("reference");
        }
        return ref != null ? String.valueOf(ref) : null;
    }

    // ─── Reference helpers ──────────────────────────────────────────────────

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
     * Finds our DEP_ reference in a CediLink webhook payload. The docs
     * show the event name but no sample body, so look in the usual places:
     * data.reference, data.object.reference, data.metadata.reference,
     * top-level reference.
     */
    @SuppressWarnings("unchecked")
    private String extractReference(Map<String, Object> payload) {
        if (payload == null) return null;
        if (payload.get("data") instanceof Map<?, ?> dm) {
            var data = (Map<String, Object>) dm;
            if (data.get("reference") != null && !data.get("reference").toString().isBlank())
                return data.get("reference").toString().trim();
            if (data.get("object") instanceof Map<?, ?> om) {
                var obj = (Map<String, Object>) om;
                if (obj.get("reference") != null && !obj.get("reference").toString().isBlank())
                    return obj.get("reference").toString().trim();
            }
            if (data.get("metadata") instanceof Map<?, ?> mm) {
                var meta = (Map<String, Object>) mm;
                if (meta.get("reference") != null && !meta.get("reference").toString().isBlank())
                    return meta.get("reference").toString().trim();
            }
        }
        if (payload.get("reference") != null && !payload.get("reference").toString().isBlank())
            return payload.get("reference").toString().trim();
        return null;
    }

    /**
     * Finds the CediLink intent id (pi_...) in a webhook payload. Looks in
     * data.id, data.object.id, data.intent_id, data.payment_intent_id,
     * top-level id / intent_id.
     */
    @SuppressWarnings("unchecked")
    private String extractIntentId(Map<String, Object> payload) {
        if (payload == null) return null;
        if (payload.get("data") instanceof Map<?, ?> dm) {
            var data = (Map<String, Object>) dm;
            for (var k : new String[]{"id", "intent_id", "payment_intent_id"}) {
                if (data.get(k) != null && !data.get(k).toString().isBlank())
                    return data.get(k).toString().trim();
            }
            if (data.get("object") instanceof Map<?, ?> om) {
                var obj = (Map<String, Object>) om;
                if (obj.get("id") != null && !obj.get("id").toString().isBlank())
                    return obj.get("id").toString().trim();
            }
        }
        if (payload.get("id") != null && !payload.get("id").toString().isBlank())
            return payload.get("id").toString().trim();
        if (payload.get("intent_id") != null && !payload.get("intent_id").toString().isBlank())
            return payload.get("intent_id").toString().trim();
        return null;
    }

    // ─── Validation helpers ─────────────────────────────────────────────────

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
            log.warn("[CediLink] phone prefix='{}' not in known Ghana MoMo ranges", p);
    }

    /** Maps a Ghana prefix to CediLink's network enum; null = let CediLink auto-detect. */
    private String networkForPrefix(String prefix) {
        if (MTN_GH_PREFIXES.contains(prefix)) return "MTN";
        if (VOD_GH_PREFIXES.contains(prefix)) return "TELECEL";
        if (ATL_GH_PREFIXES.contains(prefix)) return "AIRTELTIGO";
        return null;
    }

    /** Accepts MTN/TELECEL/AIRTELTIGO (and common aliases); null when unrecognised. */
    private String normalizeNetwork(String raw) {
        var v = raw.trim().toUpperCase();
        if (v.equals("MTN")) return "MTN";
        if (v.equals("TELECEL") || v.equals("VODAFONE") || v.equals("VOD")) return "TELECEL";
        if (v.equals("AIRTELTIGO") || v.equals("AIRTEL") || v.equals("TIGO") || v.equals("AT")) return "AIRTELTIGO";
        return null;
    }

    // ─── Signature ──────────────────────────────────────────────────────────

    /**
     * CediLink webhook signature: header X-CediLink-Signature is
     * "t=&lt;unix&gt;,v1=HMAC-SHA256(secret, t + '.' + raw body)".
     * Parses t and v1, recomputes, constant-time compares.
     */
    private boolean verifySignature(byte[] rawBody, String signature, String key) {
        if (key == null || key.isBlank()) return false;
        try {
            String t = null, v1 = null;
            for (var part : signature.split(",")) {
                var p = part.trim();
                if (p.startsWith("t=")) t = p.substring(2);
                else if (p.startsWith("v1=")) v1 = p.substring(3);
            }
            if (t == null || v1 == null) return false;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var payload = t + "." + new String(rawBody, StandardCharsets.UTF_8);
            var computed = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    v1.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[CediLink][verifySignature] HMAC error", e);
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
