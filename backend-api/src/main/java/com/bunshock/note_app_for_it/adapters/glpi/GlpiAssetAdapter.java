package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.port.AssetPort;
import com.bunshock.note_app_for_it.port.dto.AssetQuery;
import com.bunshock.note_app_for_it.port.dto.PortAsset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GLPI implementation of {@link AssetPort} — <b>skeleton, not implemented</b>. Will search
 * `/Computer` `/Peripheral` `/Phone` `/Monitor` `/Printer` + custom assets by
 * `serial`(field 5) / holder(`users_id` field 70), derive {@code availability} via
 * {@link GlpiAdapterProperties#bucketFor}, and count AVAILABLE records per (model, Location).
 */
@Component
public class GlpiAssetAdapter implements AssetPort {

    private final GlpiClient client;
    private final GlpiAdapterProperties properties;

    public GlpiAssetAdapter(GlpiClient client, GlpiAdapterProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<PortAsset> searchAssets(AssetQuery query) {
        throw notImplemented("searchAssets");
    }

    @Override
    public PortAsset getAsset(String assetId, String backend) {
        throw notImplemented("getAsset");
    }

    @Override
    public int countAvailable(String modelId, String sedeId) {
        throw notImplemented("countAvailable");
    }

    private static ApiException notImplemented(String method) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "El adaptador GLPI todavía no implementa: AssetPort." + method);
    }
}
