package com.bunshock.note_app_for_it.common.web;

import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.directory.AdApiClient;
import com.bunshock.note_app_for_it.directory.DirectoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-required connectivity snapshot for the desktop client. Distinct from
 * {@link HealthController} ({@code GET /health}) — that's the cheap, unauthenticated liveness
 * check; this one reports the state of each dependency the client cares about and needs a valid
 * session. Always {@code 200}: a {@code DOWN} dependency is data in the body, not an error.
 */
@RestController
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final JdbcTemplate jdbc;
    private final AdApiClient adApiClient;
    private final DirectoryProperties directoryProperties;
    private final CurrentUser currentUser;

    public StatusController(JdbcTemplate jdbc, AdApiClient adApiClient,
            DirectoryProperties directoryProperties, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.adApiClient = adApiClient;
        this.directoryProperties = directoryProperties;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/status")
    public StatusResponse status() {
        currentUser.require();
        return new StatusResponse(ComponentStatus.UP, databaseStatus(), directoryStatus());
    }

    private ComponentStatus databaseStatus() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ComponentStatus.UP;
        } catch (Exception e) {
            log.warn("status: database probe failed: {}", e.toString());
            return ComponentStatus.DOWN;
        }
    }

    private ComponentStatus directoryStatus() {
        if (!directoryProperties.isConfigured()) {
            return ComponentStatus.NOT_CONFIGURED;
        }
        try {
            // Cheapest real call the AD API exposes — a lookup that matches nothing. A reachable
            // directory answers 200 (empty list); an unreachable one throws DIRECTORY_UNAVAILABLE.
            adApiClient.fetch(null, null, "__status_probe__");
            return ComponentStatus.UP;
        } catch (Exception e) {
            log.warn("status: directory probe failed: {}", e.toString());
            return ComponentStatus.DOWN;
        }
    }
}
