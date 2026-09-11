package com.bunshock.note_app_for_it.notes;

import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.notes.dto.CreateNoteRequest;
import com.bunshock.note_app_for_it.notes.dto.CreateNoteResponse;
import com.bunshock.note_app_for_it.notes.dto.LostActionRequest;
import com.bunshock.note_app_for_it.notes.dto.NoteDetailResponse;
import com.bunshock.note_app_for_it.notes.dto.NoteSummaryResponse;
import com.bunshock.note_app_for_it.notes.dto.ReasonRequest;
import com.bunshock.note_app_for_it.notes.dto.ReturnActionRequest;
import com.bunshock.note_app_for_it.rbac.Permission;
import com.bunshock.note_app_for_it.rbac.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * backend-contract.md §5 (notes/history/approval) + §4 (item sync/return), v1 shape: §4's
 * "enqueue + real external write + holder-mismatch check" is replaced by a direct, synchronous
 * status flip (200, not 202/QUEUED) — no adapter to write to, matching the old, pre-redesign
 * desktop-app behavior exactly (GLPI sync there was already a manual, one-way, no-revert flag
 * with no live external write). See the approved plan's step 6.
 */
@RestController
@RequestMapping("/api/v1/notes")
public class NotesController {

    private final NotesRepository notes;
    private final CurrentUser currentUser;
    private final PermissionGuard permissionGuard;

    public NotesController(NotesRepository notes, CurrentUser currentUser, PermissionGuard permissionGuard) {
        this.notes = notes;
        this.currentUser = currentUser;
        this.permissionGuard = permissionGuard;
    }

    @PostMapping
    public ResponseEntity<CreateNoteResponse> create(@Valid @RequestBody CreateNoteRequest request) {
        CallerPrincipal caller = currentUser.require();
        if (caller.sedeId() == null) {
            // Mirrors the desktop app's mandatory-Sede block on note generation — a technician
            // with no Sede assigned cannot create a note at all (see CLAUDE.md's "Technician
            // Sede — per-note, mandatory"). Here the Sede is resolved server-side from the
            // caller's own APP_USER row, never client-supplied — see CreateNoteRequest's Javadoc.
            throw ApiException.badRequest("SEDE_NOT_ASSIGNED",
                    "El técnico no tiene una Sede asignada. Contacte a un administrador.");
        }
        // technician_name / technician_dni are snapshotted from the caller's login-time AD
        // identity (§7.6) — the real full name + DNI, or the username + null if the directory
        // was unreachable at login. Always the AD value, never a client-chosen display name.
        // Same "snapshot, don't reference" rule the desktop app uses for these two columns.
        int id = notes.createNote(request, caller.fullName(), caller.dni(), caller.sedeId());
        NoteDetailResponse created = notes.getById(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateNoteResponse(created.id(), created.approvalStatus(), created.createdAt()));
    }

    @GetMapping
    public List<NoteSummaryResponse> list(
            @RequestParam(required = false) List<String> profileTypes,
            @RequestParam(required = false) List<String> approvalStatuses,
            @RequestParam(required = false) List<Integer> sedes,
            @RequestParam(required = false) String authorSearch,
            @RequestParam(required = false) String recipientSearch,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) List<String> itemTypes,
            @RequestParam(required = false) List<String> itemBrands,
            @RequestParam(required = false) List<String> itemModels,
            @RequestParam(required = false) List<String> syncStatuses,
            @RequestParam(required = false) List<String> returnStatuses) {
        currentUser.require();
        return notes.getFiltered(new NotesFilter(
                profileTypes, approvalStatuses, sedes, authorSearch, recipientSearch, dateFrom, dateTo,
                itemTypes, itemBrands, itemModels, syncStatuses, returnStatuses));
    }

    @GetMapping("/{id}")
    public NoteDetailResponse get(@PathVariable int id) {
        currentUser.require();
        return notes.getById(id);
    }

    // ── Distinct item filter values (History filter dropdown menus) ────────

    @GetMapping("/distinct/item-types")
    public List<String> distinctItemTypes() {
        currentUser.require();
        return notes.getDistinctItemTypes();
    }

    @GetMapping("/distinct/item-brands")
    public List<String> distinctItemBrands(@RequestParam(required = false) List<String> types) {
        currentUser.require();
        return notes.getDistinctItemBrands(types);
    }

    @GetMapping("/distinct/item-models")
    public List<String> distinctItemModels(
            @RequestParam(required = false) List<String> types,
            @RequestParam(required = false) List<String> brands) {
        currentUser.require();
        return notes.getDistinctItemModels(types, brands);
    }

    // ── Most-used item pinning (ItemDialogController's Type/Brand/Model combo pinning) ──

    @GetMapping("/most-used/types")
    public List<String> mostUsedTypes(
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(defaultValue = "2") int minUses,
            @RequestParam(defaultValue = "3") int limit) {
        currentUser.require();
        return notes.getMostUsedTypeNames(windowDays, minUses, limit);
    }

    @GetMapping("/most-used/brands")
    public List<String> mostUsedBrands(
            @RequestParam String typeName,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(defaultValue = "2") int minUses,
            @RequestParam(defaultValue = "3") int limit) {
        currentUser.require();
        return notes.getMostUsedBrandNames(typeName, windowDays, minUses, limit);
    }

    @GetMapping("/most-used/models")
    public List<String> mostUsedModels(
            @RequestParam String typeName,
            @RequestParam String brandName,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(defaultValue = "2") int minUses,
            @RequestParam(defaultValue = "3") int limit) {
        currentUser.require();
        return notes.getMostUsedModelNames(typeName, brandName, windowDays, minUses, limit);
    }

    @PutMapping("/{id}/approve")
    public NoteDetailResponse approve(@PathVariable int id) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.APPROVE_NOTES, notes.getSedeIdForNote(id));
        notes.updateApprovalStatus(id, "APPROVED", null, caller.username());
        return notes.getById(id);
    }

    @PutMapping("/{id}/reject")
    public NoteDetailResponse reject(@PathVariable int id, @Valid @RequestBody ReasonRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.APPROVE_NOTES, notes.getSedeIdForNote(id));
        notes.updateApprovalStatus(id, "REJECTED", request.reason(), caller.username());
        return notes.getById(id);
    }

    // ── Item sync (GLPI dimension) ──────────────────────────────────────────

    @PostMapping("/{noteId}/items/{itemId}/sync")
    public NoteDetailResponse sync(@PathVariable int noteId, @PathVariable int itemId) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.SYNC_GLPI, notes.getSedeIdForNote(noteId));
        notes.updateItemGlpiStatus(itemId, "SYNCED", null, caller.username());
        return notes.getById(noteId);
    }

    @PostMapping("/{noteId}/items/{itemId}/reject-sync")
    public NoteDetailResponse rejectSync(@PathVariable int noteId, @PathVariable int itemId,
            @Valid @RequestBody ReasonRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.SYNC_GLPI, notes.getSedeIdForNote(noteId));
        notes.updateItemGlpiStatus(itemId, "REJECTED", request.reason(), caller.username());
        return notes.getById(noteId);
    }

    // ── Item return re-sync (GLPI_RETURN dimension) ─────────────────────────
    // Only for a returnable Provider asset whose return has been validated — the row is
    // seeded by that RETURN → RETURNED transition; a call before then is a 409.

    @PostMapping("/{noteId}/items/{itemId}/sync-return")
    public NoteDetailResponse syncReturn(@PathVariable int noteId, @PathVariable int itemId) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.SYNC_GLPI, notes.getSedeIdForNote(noteId));
        notes.updateItemGlpiReturnStatus(itemId, "SYNCED", null, caller.username());
        return notes.getById(noteId);
    }

    @PostMapping("/{noteId}/items/{itemId}/reject-sync-return")
    public NoteDetailResponse rejectSyncReturn(@PathVariable int noteId, @PathVariable int itemId,
            @Valid @RequestBody ReasonRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.SYNC_GLPI, notes.getSedeIdForNote(noteId));
        notes.updateItemGlpiReturnStatus(itemId, "REJECTED", request.reason(), caller.username());
        return notes.getById(noteId);
    }

    // ── Item return (RETURN dimension) ──────────────────────────────────────

    @PutMapping("/{noteId}/items/{itemId}/return")
    public NoteDetailResponse returnItem(@PathVariable int noteId, @PathVariable int itemId,
            @RequestBody(required = false) ReturnActionRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.VALIDATE_RETURNS, notes.getSedeIdForNote(noteId));
        if (notes.isAssetItem(itemId)) {
            notes.updateItemReturnStatus(itemId, "RETURNED", null, caller.username());
        } else {
            Integer quantity = request == null ? null : request.quantity();
            if (quantity == null) {
                throw ApiException.badRequest("QUANTITY_REQUIRED",
                        "Debe indicar la cantidad a devolver para un ítem por cantidad.");
            }
            notes.allocateCountableReturn(itemId, "RETURNED", quantity, null, caller.username());
        }
        return notes.getById(noteId);
    }

    @PutMapping("/{noteId}/items/{itemId}/lost")
    public NoteDetailResponse lostItem(@PathVariable int noteId, @PathVariable int itemId,
            @Valid @RequestBody LostActionRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.requireSedeScoped(caller, Permission.VALIDATE_RETURNS, notes.getSedeIdForNote(noteId));
        if (notes.isAssetItem(itemId)) {
            notes.updateItemReturnStatus(itemId, "LOST", request.reason(), caller.username());
        } else {
            if (request.quantity() == null) {
                throw ApiException.badRequest("QUANTITY_REQUIRED",
                        "Debe indicar la cantidad extraviada para un ítem por cantidad.");
            }
            notes.allocateCountableReturn(itemId, "LOST", request.quantity(), request.reason(), caller.username());
        }
        return notes.getById(noteId);
    }
}
