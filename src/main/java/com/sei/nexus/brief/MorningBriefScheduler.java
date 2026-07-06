package com.sei.nexus.brief;

import com.sei.nexus.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checks every minute whether any tenant's morning brief is due.
 * Mirrors the pattern used by ScheduledReportService.
 */
@Component
public class MorningBriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefScheduler.class);

    private final MorningBriefRepository briefRepository;
    private final MorningBriefService    briefService;

    public MorningBriefScheduler(MorningBriefRepository briefRepository,
                                  MorningBriefService briefService) {
        this.briefRepository = briefRepository;
        this.briefService    = briefService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void checkDueBriefs() {
        List<MorningBriefConfig> configs;
        try {
            configs = briefRepository.findAllEnabledConfigs();
        } catch (Exception e) {
            log.warn("Could not load morning brief configs: {}", e.getMessage());
            return;
        }

        for (MorningBriefConfig config : configs) {
            TenantContext.set(config.tenantSchema());
            try {
                briefService.generateIfDue(config);
            } catch (Exception e) {
                log.error("Error checking morning brief for tenant '{}': {}",
                        config.tenantSchema(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
