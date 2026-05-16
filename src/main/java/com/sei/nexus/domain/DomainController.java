package com.sei.nexus.domain;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/domains")
public class DomainController {

    private final DomainRepository domainRepository;

    public DomainController(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    @GetMapping
    public ResponseEntity<?> listDomains() {
        List<Domain> domains = domainRepository.findAll();
        return ResponseEntity.ok(domains);
    }

    @PostMapping
    public ResponseEntity<?> createOrUpdateDomain(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Domain name is required");
        }

        String domainKey = body.get("domain_key");
        if (domainKey == null || domainKey.isBlank()) {
            domainKey = Keys.key(name);
        }

        String description = body.get("description");
        String ownerTeam = body.get("owner_team");
        String ownerEmail = body.get("owner_email");
        String status = body.getOrDefault("status", "ACTIVE");

        Domain domain = new Domain(domainKey, name, description, ownerTeam, ownerEmail, status, null, null);
        domainRepository.save(domain);

        Domain saved = domainRepository.findByKey(domainKey)
                .orElseThrow(() -> new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve saved domain"));

        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{domainKey}")
    public ResponseEntity<?> archiveDomain(@PathVariable String domainKey) {
        domainRepository.findByKey(domainKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Domain not found: " + domainKey));

        int updated = domainRepository.archive(domainKey);
        if (updated == 0) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to archive domain");
        }

        return ResponseEntity.ok(Map.of("message", "Domain archived", "domain_key", domainKey));
    }
}
