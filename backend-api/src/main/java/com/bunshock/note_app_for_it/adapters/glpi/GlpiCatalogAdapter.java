package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.port.CatalogPort;
import com.bunshock.note_app_for_it.port.dto.PortModel;
import com.bunshock.note_app_for_it.port.dto.PortRef;
import com.bunshock.note_app_for_it.port.dto.PortType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GLPI implementation of {@link CatalogPort} — <b>skeleton, not implemented</b>. Every method
 * will read GLPI dropdowns ({@code <X>Type}, {@code Manufacturer}, {@code <X>Model},
 * {@code Location}, {@code Supplier}), flatten the 6+ itemtypes (N2), and fill the "Genérico"
 * fallback from {@link GlpiAdapterProperties}'s per-itemtype maps.
 */
@Component
public class GlpiCatalogAdapter implements CatalogPort {

    private final GlpiClient client;
    private final GlpiAdapterProperties properties;

    public GlpiCatalogAdapter(GlpiClient client, GlpiAdapterProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<PortType> listTypes() {
        throw notImplemented("listTypes");
    }

    @Override
    public List<PortRef> listBrands() {
        throw notImplemented("listBrands");
    }

    @Override
    public List<PortModel> listModels(String typeId) {
        throw notImplemented("listModels");
    }

    @Override
    public List<PortRef> listSedes() {
        throw notImplemented("listSedes");
    }

    @Override
    public List<PortRef> listProviders() {
        throw notImplemented("listProviders");
    }

    private static ApiException notImplemented(String method) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "El adaptador GLPI todavía no implementa: CatalogPort." + method);
    }
}
