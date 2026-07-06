package com.sei.nexus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private static final Set<String> VALID_ROLES    = Set.of("ADMIN", "ANALYST", "DOMAIN_OWNER");
    private static final Set<String> VALID_STATUSES = Set.of("ACTIVE", "INACTIVE");

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-role-key}")
    private String serviceRoleKey;

    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper          mapper;
    private final HttpClient            httpClient;

    public UserManagementService(UserProfileRepository userProfileRepository,
                                  ObjectMapper mapper) {
        this.userProfileRepository = userProfileRepository;
        this.mapper    = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public List<Map<String, Object>> listUsers(String tenantSchema) {
        return userProfileRepository.findByTenantSchema(tenantSchema)
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("email",         p.email());
                    m.put("display_name",  p.displayName());
                    m.put("role",          p.role());
                    m.put("status",        p.status());
                    m.put("tenant_schema", p.tenantSchema());
                    m.put("invited_by",    p.invitedBy());
                    m.put("created_at",    p.createdAt());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public void inviteUser(String email, String role, String displayName,
                            String tenantSchema, String inviterEmail) {
        if (email == null || !email.contains("@"))
            throw new NexusException(HttpStatus.BAD_REQUEST, "Valid email required");
        if (!VALID_ROLES.contains(role))
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid role: " + role);

        String normalised = email.toLowerCase().trim();

        userProfileRepository.findByEmail(normalised).ifPresent(existing -> {
            if (tenantSchema.equals(existing.tenantSchema()))
                throw new NexusException(HttpStatus.CONFLICT,
                        "User already exists in this workspace: " + normalised);
        });

        userProfileRepository.create(new UserProfile(
                normalised, tenantSchema, role, "INVITED",
                displayName, inviterEmail, null, null));

        try {
            callSupabaseInvite(normalised, tenantSchema, role);
        } catch (Exception e) {
            log.error("Supabase invite API call failed for {}: {}", normalised, e.getMessage());
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Profile created but invite email failed: " + e.getMessage());
        }
    }

    public void updateUser(String email, String role, String status) {
        if (role != null && !VALID_ROLES.contains(role))
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid role: " + role);
        if (status != null && !VALID_STATUSES.contains(status))
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid status: " + status);

        UserProfile existing = userProfileRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "User not found: " + email));

        userProfileRepository.updateRoleAndStatus(
                email.toLowerCase(),
                role   != null ? role   : existing.role(),
                status != null ? status : existing.status());
    }

    public void deactivateUser(String email) {
        userProfileRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "User not found: " + email));
        userProfileRepository.deactivate(email.toLowerCase());
    }

    public void resendInvite(String email) {
        UserProfile profile = userProfileRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "User not found: " + email));
        if (!"INVITED".equals(profile.status())) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Can only resend invite to users with INVITED status");
        }
        try {
            callSupabaseInvite(profile.email(), profile.tenantSchema(), profile.role());
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to resend invite: " + e.getMessage());
        }
    }

    // ── Supabase Admin invite API ─────────────────────────────────────────────

    private void callSupabaseInvite(String email, String tenantSchema, String role)
            throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "email", email,
                "data", Map.of("tenant_schema", tenantSchema, "role", role)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/auth/v1/invite"))
                .header("apikey",        serviceRoleKey)
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Supabase returned HTTP " +
                    response.statusCode() + ": " + response.body());
        }
    }
}
