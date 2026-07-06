package com.sei.nexus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates Supabase-issued JWTs and resolves tenant + role from
 * public.nexus_user_profile.
 *
 * Supports both algorithms Supabase issues:
 *  - HS256 (legacy projects): HMAC-SHA256 with SUPABASE_JWT_SECRET
 *  - ES256 (current default): ECDSA P-256, public key fetched from JWKS endpoint
 *
 * Falls through silently on any failure so NexusAuthFilter can handle
 * legacy X-Nexus-Token requests.
 */
public class SupabaseAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserProfileRepository        userProfileRepository;
    private final TenantDomainRepository       tenantDomainRepository;
    private final byte[]                       jwtSecretBytes;
    private final String                       jwksUrl;
    private final ObjectMapper                 mapper;
    private final HttpClient                   httpClient;
    private final ConcurrentHashMap<String, PublicKey> jwkCache = new ConcurrentHashMap<>();

    public SupabaseAuthFilter(UserProfileRepository userProfileRepository,
                               TenantDomainRepository tenantDomainRepository,
                               String supabaseJwtSecret,
                               String supabaseUrl,
                               ObjectMapper mapper) {
        this.userProfileRepository  = userProfileRepository;
        this.tenantDomainRepository = tenantDomainRepository;
        this.mapper     = mapper;
        this.httpClient = HttpClient.newHttpClient();
        this.jwksUrl    = supabaseUrl.replaceAll("/+$", "") + "/auth/v1/.well-known/jwks.json";

        // HS256 secret — base64url or raw UTF-8
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(supabaseJwtSecret);
        } catch (IllegalArgumentException e) {
            decoded = supabaseJwtSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.jwtSecretBytes = decoded;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String jwt = authHeader.substring(BEARER_PREFIX.length()).trim();
                if (!jwt.isBlank()) resolveAuthentication(jwt);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void resolveAuthentication(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return;

            // Decode header to find algorithm and kid
            String headerJson = new String(
                    Base64.getUrlDecoder().decode(padBase64(parts[0])),
                    StandardCharsets.UTF_8);
            String alg = extractStringClaim(headerJson, "alg");
            String kid = extractStringClaim(headerJson, "kid");

            String signingInput = parts[0] + "." + parts[1];
            byte[] rawSig = Base64.getUrlDecoder().decode(padBase64(parts[2]));

            boolean verified;
            if ("ES256".equals(alg)) {
                verified = verifyEs256(signingInput, rawSig, kid);
            } else if ("HS256".equals(alg)) {
                verified = verifyHs256(signingInput, rawSig);
            } else {
                log.debug("Unsupported JWT algorithm: {}", alg);
                return;
            }

            if (!verified) {
                log.debug("Supabase JWT signature verification failed (alg={})", alg);
                return;
            }

            // Decode and validate payload
            String payload = new String(
                    Base64.getUrlDecoder().decode(padBase64(parts[1])),
                    StandardCharsets.UTF_8);

            long exp = extractLongClaim(payload, "exp");
            if (exp <= 0 || Instant.now().getEpochSecond() > exp) {
                log.debug("Supabase JWT expired");
                return;
            }

            String email = extractStringClaim(payload, "email");
            if (email == null || email.isBlank()) return;

            // Resolve tenant + role — explicit profile first, domain auto-provision as fallback
            String normalizedEmail = email.toLowerCase();
            UserProfile profile = userProfileRepository.findByEmail(normalizedEmail)
                    .orElseGet(() -> autoProvisionFromDomain(normalizedEmail));

            if (profile == null || "INACTIVE".equals(profile.status())) return;

            if ("INVITED".equals(profile.status())) {
                userProfileRepository.updateRoleAndStatus(
                        profile.email(), profile.role(), "ACTIVE");
            }

            TenantContext.set(profile.tenantSchema());

            UserAccount account = new UserAccount(
                    profile.email(),
                    profile.displayName() != null ? profile.displayName() : profile.email(),
                    null,
                    profile.role(),
                    "ACTIVE",
                    profile.createdAt(),
                    profile.updatedAt());

            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + profile.role()));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(account, null, authorities));

        } catch (Exception e) {
            log.debug("Supabase JWT processing failed: {}", e.getMessage());
        }
    }

    // ── Domain auto-provisioning ──────────────────────────────────────────────

    /**
     * Called when an SSO user's email is not in nexus_user_profile.
     * Extracts the email domain, looks it up in nexus_tenant_domain, and if found
     * auto-creates a profile row so the user can log in without a pre-invite.
     */
    private UserProfile autoProvisionFromDomain(String email) {
        int at = email.indexOf('@');
        if (at < 0) return null;
        String domain = email.substring(at + 1);

        return tenantDomainRepository.findByDomain(domain).map(td -> {
            UserProfile profile = new UserProfile(
                    email, td.tenantSchema(), td.defaultRole(),
                    "ACTIVE", null, "sso-auto-provision", null, null);
            try {
                userProfileRepository.create(profile);
                log.info("SSO auto-provisioned {} into tenant {} (domain: {})",
                        email, td.tenantSchema(), domain);
            } catch (Exception e) {
                log.warn("Auto-provision insert failed for {}: {}", email, e.getMessage());
            }
            return profile;
        }).orElse(null);
    }

    // ── ES256 verification ────────────────────────────────────────────────────

    private boolean verifyEs256(String signingInput, byte[] rawSig, String kid) throws Exception {
        PublicKey publicKey = getPublicKey(kid);
        if (publicKey == null) return false;

        // JWT ES256 signatures are raw r||s (64 bytes). Java needs DER-encoded.
        byte[] derSig = rawSignatureToDer(rawSig);

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(publicKey);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return sig.verify(derSig);
    }

    private PublicKey getPublicKey(String kid) {
        if (kid != null && jwkCache.containsKey(kid)) return jwkCache.get(kid);
        refreshJwks();
        return kid != null ? jwkCache.get(kid) : jwkCache.values().stream().findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private void refreshJwks() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUrl))
                    .GET().build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> jwks = mapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
            if (keys == null) return;

            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = params.getParameterSpec(ECParameterSpec.class);

            for (Map<String, Object> jwk : keys) {
                String keyId = (String) jwk.get("kid");
                String x = (String) jwk.get("x");
                String y = (String) jwk.get("y");
                if (keyId == null || x == null || y == null) continue;

                byte[] xBytes = Base64.getUrlDecoder().decode(padBase64(x));
                byte[] yBytes = Base64.getUrlDecoder().decode(padBase64(y));
                ECPoint point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
                ECPublicKeySpec spec = new ECPublicKeySpec(point, p256);
                PublicKey pk = KeyFactory.getInstance("EC").generatePublic(spec);
                jwkCache.put(keyId, pk);
                log.debug("Cached JWK key: {}", keyId);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh Supabase JWKS: {}", e.getMessage());
        }
    }

    /**
     * Converts a raw r||s ECDSA signature (64 bytes for P-256) to DER format
     * as expected by Java's Signature.verify().
     */
    private byte[] rawSignatureToDer(byte[] rawSig) {
        if (rawSig.length != 64) throw new IllegalArgumentException(
                "Expected 64-byte raw ECDSA signature, got " + rawSig.length);

        byte[] r = Arrays.copyOfRange(rawSig, 0, 32);
        byte[] s = Arrays.copyOfRange(rawSig, 32, 64);

        // Prepend 0x00 if high bit is set (keeps value positive in DER)
        if ((r[0] & 0x80) != 0) r = prepend(r, (byte) 0x00);
        if ((s[0] & 0x80) != 0) s = prepend(s, (byte) 0x00);

        int totalLen = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + totalLen];
        der[0] = 0x30;
        der[1] = (byte) totalLen;
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    private byte[] prepend(byte[] arr, byte prefix) {
        byte[] result = new byte[arr.length + 1];
        result[0] = prefix;
        System.arraycopy(arr, 0, result, 1, arr.length);
        return result;
    }

    // ── HS256 verification ────────────────────────────────────────────────────

    private boolean verifyHs256(String signingInput, byte[] actualSig) throws Exception {
        byte[] expectedSig = hmacSha256(signingInput.getBytes(StandardCharsets.US_ASCII));
        return constantTimeEquals(expectedSig, actualSig);
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwtSecretBytes, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String padBase64(String s) {
        int pad = s.length() % 4;
        if (pad == 2) return s + "==";
        if (pad == 3) return s + "=";
        return s;
    }

    private String extractStringClaim(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon  = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return null;
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote2 < 0) return null;
        return json.substring(quote1 + 1, quote2);
    }

    private long extractLongClaim(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return 0;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (start == end) return 0;
        try { return Long.parseLong(json.substring(start, end)); }
        catch (NumberFormatException e) { return 0; }
    }
}
