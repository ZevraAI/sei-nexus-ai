package com.sei.nexus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.auth.AuthRepository;
import com.sei.nexus.auth.ImpersonationFilter;
import com.sei.nexus.auth.NexusAuthFilter;
import com.sei.nexus.auth.SupabaseAuthFilter;
import com.sei.nexus.auth.TenantDomainRepository;
import com.sei.nexus.auth.UserProfileRepository;
import com.sei.nexus.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthRepository         authRepository;
    private final TenantRepository       tenantRepository;
    private final UserProfileRepository  userProfileRepository;
    private final TenantDomainRepository tenantDomainRepository;
    private final ObjectMapper           objectMapper;

    @Value("${supabase.jwt-secret}")
    private String supabaseJwtSecret;

    @Value("${supabase.url}")
    private String supabaseUrl;

    public SecurityConfig(AuthRepository authRepository,
                           TenantRepository tenantRepository,
                           UserProfileRepository userProfileRepository,
                           TenantDomainRepository tenantDomainRepository,
                           ObjectMapper objectMapper) {
        this.authRepository         = authRepository;
        this.tenantRepository       = tenantRepository;
        this.userProfileRepository  = userProfileRepository;
        this.tenantDomainRepository = tenantDomainRepository;
        this.objectMapper           = objectMapper;
    }

    @Bean
    public SupabaseAuthFilter supabaseAuthFilter() {
        return new SupabaseAuthFilter(userProfileRepository, tenantDomainRepository,
                supabaseJwtSecret, supabaseUrl, objectMapper);
    }

    @Bean
    public NexusAuthFilter nexusAuthFilter() {
        return new NexusAuthFilter(authRepository, tenantRepository);
    }

    @Bean
    public ImpersonationFilter impersonationFilter() {
        return new ImpersonationFilter(tenantRepository);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                    // CORS preflight — must pass before auth check
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Public auth endpoints — no token required
                    .requestMatchers("/auth/signup", "/auth/login").permitAll()
                    // Automation webhook triggers — public, protected by slug secrecy
                    .requestMatchers(HttpMethod.POST, "/automations/run/**").permitAll()
                    // Health and metrics — no token required
                    .requestMatchers("/actuator/**").permitAll()
                    // Admin tenant management — authenticated + ADMIN role enforced in controller
                    .requestMatchers("/admin/tenants/**").authenticated()
                    // Everything else requires authentication
                    .anyRequest().authenticated()
            )
            // Supabase JWT filter runs first; NexusAuthFilter handles legacy X-Nexus-Token
            // ImpersonationFilter overrides TenantContext for platform admins
            .addFilterBefore(supabaseAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(nexusAuthFilter(), SupabaseAuthFilter.class)
            .addFilterAfter(impersonationFilter(), NexusAuthFilter.class);

        return http.build();
    }
}
