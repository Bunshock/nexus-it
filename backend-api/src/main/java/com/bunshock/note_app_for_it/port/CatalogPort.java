package com.bunshock.note_app_for_it.port;

import com.bunshock.note_app_for_it.port.dto.PortModel;
import com.bunshock.note_app_for_it.port.dto.PortRef;
import com.bunshock.note_app_for_it.port.dto.PortType;

import java.util.List;

/**
 * Read-only catalog, backed by the external inventory system (backend-contract.md §3.1/§3.3).
 * For GLPI: types = the flattened set of itemtypes + their {@code <X>Type} dropdowns (N2), brands
 * = {@code Manufacturer}, models = {@code <X>Model}, sedes = {@code Location}, providers =
 * {@code Supplier}. No CRUD — catalog management is done in the backend, not the app (D3).
 */
public interface CatalogPort {

    List<PortType> listTypes();

    List<PortRef> listBrands();

    List<PortModel> listModels(String typeId);

    List<PortRef> listSedes();

    List<PortRef> listProviders();
}
