package com.bunshock.note_app_for_it_frontend.services.history;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * {@code IHistoryService} over the middleware's {@code /api/v1/notes} endpoints (Phase B). Unlike
 * {@code RestEquipmentService}, reads here are always live — a note's approval/sync/return state
 * can change from another admin action at any moment, and an SWR-style cache would risk an admin
 * acting on a stale status.
 *
 * <p>{@code IHistoryService}'s item-status-change methods only ever carry an {@code itemId}, not
 * the parent {@code noteId} the middleware's sync/return URLs need — the interface predates the
 * REST cutover and wasn't widened to thread {@code noteId} through every controller call site (see
 * every real caller: they already hold the loaded {@code NoteReport}, just never pass its id
 * through). {@link #resolveNoteId(int)} makes one small lookup call instead.
 */
public class RestHistoryService implements IHistoryService {

    private static final String BASE = "/api/v1/notes";

    private final MiddlewareClient client;

    public RestHistoryService(MiddlewareClient client) {
        this.client = client;
    }

    // ── create ───────────────────────────────────────────────────────────

    @Override
    public int save(NoteReport report) {
        CreateNoteBody body = toCreateBody(report);
        NoteCreatedDto created = client.post(BASE, body, NoteCreatedDto.class);
        return created.id();
    }

    private CreateNoteBody toCreateBody(NoteReport r) {
        List<CreateItemBody> items = r.getItems() == null ? List.of()
                : r.getItems().stream().map(this::toCreateItemBody).collect(Collectors.toList());
        Integer providerId = r.getProviderId() > 0 ? r.getProviderId() : null;
        return new CreateNoteBody(
                r.getProfileType(), r.getUserName(), r.getUserDni(), r.getUserEmail(),
                r.getMotivo(), r.getAreaEvento(), r.getFailureCause(), r.getFailureDetails(),
                providerId, r.getCuit(), r.getResponsibleName(), r.getResponsibleDni(),
                r.getObservations(), r.getShippingInfoId(), r.getDestinationLabel(),
                r.getAddress(), r.getRecipients(), items);
    }

    private CreateItemBody toCreateItemBody(NoteReportItem item) {
        return new CreateItemBody(
                item.isAsset() ? "ASSET" : "COUNTABLE",
                item.getTypeId(), item.getBrandId(), item.getModelId(),
                item.getSerialNumber(), item.getAf(),
                item.isAsset() ? null : item.getQuantity(),
                item.getObservations(), item.isModifiesStock(), item.getModifiesStockReason());
    }

    // ── read ─────────────────────────────────────────────────────────────

    @Override
    public List<NoteReport> getAll() {
        return getFiltered(new HistoryFilter());
    }

    @Override
    public NoteReport getById(int id) {
        return toReport(client.get(BASE + "/" + id, NoteDetailDto.class));
    }

    @Override
    public List<NoteReport> getFiltered(HistoryFilter filter) {
        List<NoteSummaryDto> rows = client.get(BASE + buildQuery(filter), new TypeReference<List<NoteSummaryDto>>() {});
        return rows.stream().map(this::toReport).collect(Collectors.toList());
    }

    @Override
    public List<NoteReport> getPendingGlpiSync() {
        return getFiltered(HistoryFilter.pendingGlpiSync());
    }

    @Override
    public List<NoteReport> getPendingApproval() {
        return getFiltered(HistoryFilter.pendingApproval());
    }

    private String buildQuery(HistoryFilter f) {
        StringBuilder sb = new StringBuilder();
        appendMulti(sb, "profileTypes", f.getProfileTypes());
        appendMulti(sb, "approvalStatuses", f.getApprovalStatuses());
        appendMulti(sb, "sedes", resolveSedeIds(f.getSedes()));
        appendSingle(sb, "authorSearch", f.getAuthorSearch());
        appendSingle(sb, "recipientSearch", f.getRecipientSearch());
        appendSingle(sb, "dateFrom", f.getFromDate() != null ? f.getFromDate().toString() : null);
        appendSingle(sb, "dateTo", f.getToDate() != null ? f.getToDate().toString() : null);
        appendMulti(sb, "itemTypes", f.getItemTypes());
        appendMulti(sb, "itemBrands", f.getItemBrands());
        appendMulti(sb, "itemModels", f.getItemModels());
        appendMulti(sb, "syncStatuses", f.getGlpiStatuses());
        return sb.length() == 0 ? "" : "?" + sb;
    }

    // HistoryFilter.sedes holds Sede *names* (matching every filter UI in the app, sourced from
    // IEquipmentService.getAllSedes()) — the middleware's own filter takes ids, so this resolves
    // the small Sede catalog fresh on every call rather than adding a cross-service dependency on
    // RestEquipmentService's own cache for what's normally a handful of rows.
    private List<Integer> resolveSedeIds(List<String> sedeNames) {
        if (sedeNames == null || sedeNames.isEmpty()) return null;
        List<SedeDto> all = client.get("/api/v1/catalog/sedes", new TypeReference<List<SedeDto>>() {});
        return all.stream()
                .filter(s -> sedeNames.stream().anyMatch(n -> n.equalsIgnoreCase(s.name())))
                .map(SedeDto::id)
                .collect(Collectors.toList());
    }

    private static void appendMulti(StringBuilder sb, String key, List<?> values) {
        if (values == null || values.isEmpty()) return;
        for (Object v : values) {
            appendRaw(sb, key, String.valueOf(v));
        }
    }

    private static void appendSingle(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) return;
        appendRaw(sb, key, value);
    }

    private static void appendRaw(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    // ── approval ─────────────────────────────────────────────────────────

    @Override
    public void updateNoteApprovalStatus(int reportId, String status, String rejectionReason) {
        if ("APPROVED".equals(status)) {
            client.put(BASE + "/" + reportId + "/approve", null);
        } else {
            client.put(BASE + "/" + reportId + "/reject", new ReasonBody(rejectionReason));
        }
    }

    // ── item sync (GLPI) ─────────────────────────────────────────────────

    @Override
    public void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {
        int noteId = resolveNoteId(itemId);
        if (status == GlpiStatus.SYNCED) {
            client.post(BASE + "/" + noteId + "/items/" + itemId + "/sync", null);
        } else if (status == GlpiStatus.REJECTED) {
            client.post(BASE + "/" + noteId + "/items/" + itemId + "/reject-sync", new ReasonBody(reason));
        } else {
            throw new IllegalArgumentException("Unsupported GLPI status transition: " + status);
        }
    }

    @Override
    public void updateItemGlpiReturnStatus(int itemId, GlpiStatus status, String reason) {
        int noteId = resolveNoteId(itemId);
        if (status == GlpiStatus.SYNCED) {
            client.post(BASE + "/" + noteId + "/items/" + itemId + "/sync-return", null);
        } else if (status == GlpiStatus.REJECTED) {
            client.post(BASE + "/" + noteId + "/items/" + itemId + "/reject-sync-return", new ReasonBody(reason));
        } else {
            throw new IllegalArgumentException("Unsupported GLPI return status transition: " + status);
        }
    }

    // ── item return (whole-item + partial countable) ────────────────────

    @Override
    public void updateItemReturnStatus(int itemId, ReturnStatus status, String reason) {
        int noteId = resolveNoteId(itemId);
        if (status == ReturnStatus.RETURNED) {
            client.put(BASE + "/" + noteId + "/items/" + itemId + "/return", null);
        } else if (status == ReturnStatus.LOST) {
            client.put(BASE + "/" + noteId + "/items/" + itemId + "/lost", new LostBody(reason, null));
        } else {
            throw new IllegalArgumentException("Unsupported return status transition: " + status);
        }
    }

    @Override
    public void allocateCountableReturn(int itemId, ReturnStatus status, int quantity, String reason) {
        int noteId = resolveNoteId(itemId);
        if (status == ReturnStatus.RETURNED) {
            client.put(BASE + "/" + noteId + "/items/" + itemId + "/return", new QuantityBody(quantity));
        } else if (status == ReturnStatus.LOST) {
            client.put(BASE + "/" + noteId + "/items/" + itemId + "/lost", new LostBody(reason, quantity));
        } else {
            throw new IllegalArgumentException("Unsupported allocation status: " + status);
        }
    }

    private int resolveNoteId(int itemId) {
        return client.get(BASE + "/items/" + itemId + "/note-id", Integer.class);
    }

    // ── distinct item filter values / most-used pinning ─────────────────

    @Override
    public List<String> getDistinctItemTypes() {
        return client.get(BASE + "/distinct/item-types", new TypeReference<List<String>>() {});
    }

    @Override
    public List<String> getDistinctItemBrands(List<String> types) {
        StringBuilder qs = new StringBuilder();
        appendMulti(qs, "types", types);
        return client.get(BASE + "/distinct/item-brands" + (qs.length() == 0 ? "" : "?" + qs),
                new TypeReference<List<String>>() {});
    }

    @Override
    public List<String> getDistinctItemModels(List<String> types, List<String> brands) {
        StringBuilder qs = new StringBuilder();
        appendMulti(qs, "types", types);
        appendMulti(qs, "brands", brands);
        return client.get(BASE + "/distinct/item-models" + (qs.length() == 0 ? "" : "?" + qs),
                new TypeReference<List<String>>() {});
    }

    @Override
    public List<String> getMostUsedTypeNames(int windowDays, int minUses, int limit) {
        return client.get(BASE + "/most-used/types?windowDays=" + windowDays + "&minUses=" + minUses + "&limit=" + limit,
                new TypeReference<List<String>>() {});
    }

    @Override
    public List<String> getMostUsedBrandNames(String typeName, int windowDays, int minUses, int limit) {
        return client.get(BASE + "/most-used/brands?typeName=" + encode(typeName)
                + "&windowDays=" + windowDays + "&minUses=" + minUses + "&limit=" + limit,
                new TypeReference<List<String>>() {});
    }

    @Override
    public List<String> getMostUsedModelNames(String typeName, String brandName, int windowDays, int minUses, int limit) {
        return client.get(BASE + "/most-used/models?typeName=" + encode(typeName) + "&brandName=" + encode(brandName)
                + "&windowDays=" + windowDays + "&minUses=" + minUses + "&limit=" + limit,
                new TypeReference<List<String>>() {});
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── mapping: middleware DTOs -> desktop models ──────────────────────

    private NoteReport toReport(NoteSummaryDto d) {
        NoteReport r = new NoteReport();
        r.setId(d.id());
        r.setCreatedAt(parseCreatedAt(d.createdAt()));
        r.setProfileType(d.profileType());
        r.setApprovalStatus(d.approvalStatus());
        r.setRejectionReason(d.rejectionReason());
        r.setAuthorName(d.authorName());
        r.setAuthorDni(d.authorDni());
        r.setRecipientDisplay(d.recipient());
        r.setMotivo(d.motivo());
        r.setSede(d.sede());
        r.setSedeId(d.sedeId() != null ? d.sedeId() : 0);
        r.setAssetItemCount(d.assetItemCount());
        r.setCountableItemCount(d.countableItemCount());
        r.setPendingItemCount(d.pendingItemCount());
        r.setSyncedItemCount(d.syncedItemCount());
        r.setRejectedItemCount(d.rejectedItemCount());
        r.setReturnPendingItemCount(d.returnPendingItemCount());
        r.setReturnedItemCount(d.returnedItemCount());
        r.setLostItemCount(d.lostItemCount());
        r.setDestinationLabel(d.destinationLabel());
        r.setAddress(d.destinationAddress());
        r.setRecipients(d.destinationRecipients());
        return r;
    }

    private NoteReport toReport(NoteDetailDto d) {
        NoteReport r = new NoteReport();
        r.setId(d.id());
        r.setCreatedAt(parseCreatedAt(d.createdAt()));
        r.setProfileType(d.profileType());
        r.setApprovalStatus(d.approvalStatus());
        r.setRejectionReason(d.rejectionReason());
        r.setAuthorName(d.authorName());
        r.setAuthorDni(d.authorDni());
        r.setObservations(d.observations());
        r.setSede(d.sede());
        r.setSedeId(d.sedeId());
        r.setUserName(d.userName());
        r.setUserDni(d.userDni());
        r.setUserEmail(d.userEmail());
        r.setMotivo(d.motivo());
        r.setFailureCause(d.failureCause());
        r.setFailureDetails(d.failureDetails());
        r.setAreaEvento(d.areaEvento());
        r.setProviderName(d.providerName());
        r.setProviderId(d.providerId() != null ? d.providerId() : 0);
        r.setCuit(d.cuit());
        r.setResponsibleName(d.responsibleName());
        r.setResponsibleDni(d.responsibleDni());
        r.setStockApplied(d.stockApplied());
        r.setDestinationSedeId(d.destinationSedeId());
        r.setDestinationLabel(d.destinationLabel());
        r.setAddress(d.destinationAddress());
        r.setRecipients(d.destinationRecipients());
        List<NoteReportItem> items = d.items() == null ? List.of()
                : d.items().stream().map(this::toReportItem).collect(Collectors.toList());
        r.setItems(items);
        return r;
    }

    private NoteReportItem toReportItem(NoteItemDto d) {
        NoteReportItem item = new NoteReportItem();
        item.setId(d.id());
        item.setTypeId(d.typeId());
        item.setBrandId(d.brandId());
        item.setModelId(d.modelId());
        item.setTypeName(d.typeName());
        item.setBrandName(d.brandName());
        item.setModelName(d.modelName());
        item.setSerialNumber(d.serialNumber());
        item.setAf(d.af());
        item.setQuantity(d.quantity());
        item.setObservations(d.observations());
        item.setAsset(d.asset());
        item.setModifiesStock(d.modifiesStock());
        item.setModifiesStockReason(d.modifiesStockReason());
        item.setGlpiStatus(GlpiStatus.fromString(d.glpiStatus()));
        item.setGlpiRejectionReason(d.glpiRejectionReason());
        item.setGlpiStatusUpdatedAt(d.glpiStatusUpdatedAt());
        item.setGlpiReturnStatus(GlpiStatus.fromString(d.glpiReturnStatus()));
        item.setGlpiReturnRejectionReason(d.glpiReturnRejectionReason());
        item.setGlpiReturnStatusUpdatedAt(d.glpiReturnStatusUpdatedAt());
        item.setReturnStatus(ReturnStatus.fromString(d.returnStatus()));
        item.setReturnRejectionReason(d.returnRejectionReason());
        item.setReturnStatusUpdatedAt(d.returnStatusUpdatedAt());
        item.setReturnedQuantity(d.returnedQuantity());
        item.setLostQuantity(d.lostQuantity());
        item.setReturnedBatches(toBatches(d.returnedBatches()));
        item.setLostBatches(toBatches(d.lostBatches()));
        return item;
    }

    private List<ReturnAllocationBatch> toBatches(List<BatchDto> batches) {
        if (batches == null || batches.isEmpty()) return new ArrayList<>();
        return batches.stream()
                .map(b -> new ReturnAllocationBatch(b.quantity(), b.reason(), b.updatedAt()))
                .collect(Collectors.toList());
    }

    private static LocalDateTime parseCreatedAt(String raw) {
        return raw == null ? null : LocalDateTime.parse(raw);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    private record CreateNoteBody(
            String profileType, String userName, String userDni, String userEmail,
            String motivo, String areaEvento, String failureCause, String failureDetails,
            Integer providerId, String cuit, String responsibleName, String responsibleDni,
            String observations, Integer shippingInfoId, String destinationLabel,
            String destinationAddress, String destinationRecipients, List<CreateItemBody> items) {
    }

    private record CreateItemBody(
            String kind, int typeId, int brandId, int modelId,
            String serialNumber, String af, Integer quantity,
            String observations, boolean modifiesStock, String modifiesStockReason) {
    }

    private record ReasonBody(String reason) {}
    private record LostBody(String reason, Integer quantity) {}
    private record QuantityBody(Integer quantity) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteCreatedDto(int id, String approvalStatus, String createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SedeDto(int id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteSummaryDto(
            int id, String createdAt, String profileType, String approvalStatus, String rejectionReason,
            String authorName, String authorDni, String recipient, String motivo, String sede, Integer sedeId,
            int assetItemCount, int countableItemCount,
            int pendingItemCount, int syncedItemCount, int rejectedItemCount,
            int returnPendingItemCount, int returnedItemCount, int lostItemCount,
            String destinationLabel, String destinationAddress, String destinationRecipients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteDetailDto(
            int id, String createdAt, String profileType, String approvalStatus, String rejectionReason,
            String authorName, String authorDni, String observations, String sede, int sedeId,
            String userName, String userDni, String userEmail, String motivo,
            String failureCause, String failureDetails, String areaEvento,
            String providerName, Integer providerId, String cuit, String responsibleName, String responsibleDni,
            boolean stockApplied,
            Integer destinationSedeId, String destinationLabel, String destinationAddress, String destinationRecipients,
            List<NoteItemDto> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteItemDto(
            int id, int typeId, int brandId, int modelId,
            String typeName, String brandName, String modelName,
            String serialNumber, String af, int quantity,
            String observations, boolean modifiesStock, String modifiesStockReason,
            boolean asset,
            String glpiStatus, String glpiRejectionReason, String glpiStatusUpdatedAt,
            String glpiReturnStatus, String glpiReturnRejectionReason, String glpiReturnStatusUpdatedAt,
            String returnStatus, String returnRejectionReason, String returnStatusUpdatedAt,
            int returnedQuantity, int lostQuantity,
            List<BatchDto> returnedBatches, List<BatchDto> lostBatches) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BatchDto(int quantity, String reason, String updatedAt) {}
}
