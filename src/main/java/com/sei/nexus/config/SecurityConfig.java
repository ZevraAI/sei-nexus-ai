package com.sei.nexus.config;

import com.sei.nexus.auth.AuthRepository;
import com.sei.nexus.auth.NexusAuthFilter;
import com.sei.nexus.tenant.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthRepository   authRepository;
    private final TenantRepository tenantRepository;

    public SecurityConfig(AuthRepository authRepository,
                           TenantRepository tenantRepository) {
        this.authRepository   = authRepository;
        this.tenantRepository = tenantRepository;
    }

    @Bean
    public NexusAuthFilter nexusAuthFilter() {
        return new NexusAuthFilter(authRepository, tenantRepository);
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
                    // Public auth endpoints — no token required
                    .requestMatchers("/auth/signup", "/auth/login").permitAll()
                    // Health and metrics — no token required
                    .requestMatchers("/actuator/**").permitAll()
                    // Admin tenant management — authenticated + ADMIN role enforced in controller
                    .requestMatchers("/admin/tenants/**").authenticated()
                    // Everything else requires authentication
                    .anyRequest().authenticated()
            )
            .addFilterBefore(nexusAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
