package com.bunshock.note_app_for_it.port;

import com.bunshock.note_app_for_it.port.dto.AssetQuery;
import com.bunshock.note_app_for_it.port.dto.PortAsset;

import java.util.List;

/**
 * Read-only asset/stock lookup against the external inventory system
 * (backend-contract.md §3.2/§3.4/§3.5). Stock is always a live-derived count of AVAILABLE
 * records — there is no stored counter (D4).
 */
public interface AssetPort {

    List<PortAsset> searchAssets(AssetQuery query);

    PortAsset getAsset(String assetId, String backend);

    /** Live count of AVAILABLE records for a model at a Sede — feeds the pre-generation shortage warning (D4). */
    int countAvailable(String modelId, String sedeId);
}
