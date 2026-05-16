package com.sei.nexus.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Registers the multi-tenancy DataSource infrastructure.
 *
 * <p>Defining a {@code @Primary DataSource} bean here implicitly suppresses
 * Spring Boot's {@code DataSourceAutoConfiguration} (which uses
 * {@code @ConditionalOnMissingBean(DataSource.class)}).
 *
 * <p>Two beans:
 * <ul>
 *   <li>{@code rawDataSource} — plain {@link HikariDataSource} built directly
 *       from {@code spring.datasource.*} properties; used by
 *       {@link TenantProvisioningService} for schema DDL and programmatic Flyway
 *       migrations that must bypass tenant-aware routing.</li>
 *   <li>{@code dataSource} ({@code @Primary}) — {@link TenantAwareDataSource}
 *       wrapping the raw pool; sets {@code search_path} on every connection
 *       checkout.  Flyway auto-config uses this bean too: because
 *       {@link TenantContext} is always empty at startup,
 *       {@code search_path} defaults to {@code public} — correct for Flyway.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class TenantConfig {

    @Bean("rawDataSource")
    public HikariDataSource rawDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("rawDataSource") HikariDataSource raw) {
        return new TenantAwareDataSource(raw);
    }
}
