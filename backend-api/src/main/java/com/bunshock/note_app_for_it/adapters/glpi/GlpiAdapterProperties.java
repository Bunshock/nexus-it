package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.port.dto.Availability;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code middleware.glpi.*} — all the per-instance GLPI mapping config (D1: "field/status/
 * location maps are config inside the adapter"). The value maps are pre-filled in
 * {@code application.yml} with the decisions from 2026-09-07 (see
 * {@code backend-api/glpi-adapter-notes.md}); the generic-id and Sede→Location maps ship empty
 * and are filled once the GLPI admin creates the "Genérico" rows and confirms the Sede list —
 * no code change, just config.
 */
@ConfigurationProperties(prefix = "middleware.glpi")
public class GlpiAdapterProperties {

    /** GLPI HL API base, e.g. {@code https://glpi.example/api.php/v1}. Blank → {@code 503 GLPI_NOT_CONFIGURED}. */
    private String baseUrl = "";

    /** Shared {@code App-Token}, one per deployment, admin-set (via {@code MIDDLEWARE_GLPI_APPTOKEN}). */
    private String appToken = "";

    /** GLPI {@code states_id} → {@link Availability} bucket (E1a). */
    private Map<Integer, Availability> stateBuckets = new LinkedHashMap<>();

    /** Bucket for a state not in {@link #stateBuckets}, and for {@code states_id = 0}/null. */
    private Availability defaultBucket = Availability.UNAVAILABLE;

    /** Note movement key ({@code MovementKind.name()}) → GLPI {@code states_id} to write. */
    private Map<String, Integer> syncTargets = new LinkedHashMap<>();

    /** The one global "Genérico" GLPI {@code Manufacturer} id. Null until the row exists. */
    private Integer genericManufacturerId;

    /** GLPI itemtype ({@code Computer}, {@code Peripheral}, …) → "Genérico" {@code <X>Type} id. */
    private Map<String, Integer> genericTypeIds = new LinkedHashMap<>();

    /** GLPI itemtype → "Genérico" {@code <X>Model} id. */
    private Map<String, Integer> genericModelIds = new LinkedHashMap<>();

    /** Sede name → GLPI {@code Location} id. Built from {@code GET /Location} (E2a). */
    private Map<String, Integer> sedeLocationIds = new LinkedHashMap<>();

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && appToken != null && !appToken.isBlank();
    }

    /** The bucket for a GLPI {@code states_id} (null / unmapped → {@link #defaultBucket}). */
    public Availability bucketFor(Integer stateId) {
        if (stateId == null) {
            return defaultBucket;
        }
        return stateBuckets.getOrDefault(stateId, defaultBucket);
    }

    /** The GLPI {@code states_id} to write for a movement, or null if the key isn't mapped. */
    public Integer syncTargetFor(String movementKey) {
        return syncTargets.get(movementKey);
    }

    // ── getters / setters (required by @ConfigurationProperties) ──────────────

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAppToken() { return appToken; }
    public void setAppToken(String appToken) { this.appToken = appToken; }

    public Map<Integer, Availability> getStateBuckets() { return stateBuckets; }
    public void setStateBuckets(Map<Integer, Availability> stateBuckets) { this.stateBuckets = stateBuckets; }

    public Availability getDefaultBucket() { return defaultBucket; }
    public void setDefaultBucket(Availability defaultBucket) { this.defaultBucket = defaultBucket; }

    public Map<String, Integer> getSyncTargets() { return syncTargets; }
    public void setSyncTargets(Map<String, Integer> syncTargets) { this.syncTargets = syncTargets; }

    public Integer getGenericManufacturerId() { return genericManufacturerId; }
    public void setGenericManufacturerId(Integer genericManufacturerId) { this.genericManufacturerId = genericManufacturerId; }

    public Map<String, Integer> getGenericTypeIds() { return genericTypeIds; }
    public void setGenericTypeIds(Map<String, Integer> genericTypeIds) { this.genericTypeIds = genericTypeIds; }

    public Map<String, Integer> getGenericModelIds() { return genericModelIds; }
    public void setGenericModelIds(Map<String, Integer> genericModelIds) { this.genericModelIds = genericModelIds; }

    public Map<String, Integer> getSedeLocationIds() { return sedeLocationIds; }
    public void setSedeLocationIds(Map<String, Integer> sedeLocationIds) { this.sedeLocationIds = sedeLocationIds; }
}
