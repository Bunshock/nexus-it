package com.bunshock.note_app_for_it.audit;

import com.bunshock.note_app_for_it.audit.dto.AuditAdminActionEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditItemStatusEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditLoginEntry;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §6 — read-only. No dedicated {@code VIEW_AUDIT} permission exists in the ported 12-value
 * {@code Permission} enum (audit reading was never an in-app UI feature in the desktop app's own
 * history), so this gates on role directly rather than a permission.
 *
 * <p><b>v1: SUPERADMIN only.</b> The target contract (§7.5 H4) wants a plain ADMIN to see their
 * own Sede's audit rows and a SUPERADMIN to see all. But only {@code /audit/item-status} has a
 * clean Sede link ({@code NOTE_ITEM} → {@code NOTE_REPORT.sede_id}); {@code /audit/login} (an
 * auth event, username only) and {@code /audit/admin-actions} (generic {@code target_type}/
 * {@code target_id} text) have no reliable per-row Sede. Rather than invent a fuzzy Sede
 * inference for an audit-reading UI that doesn't exist yet, v1 restricts all three to
 * SUPERADMIN — which also makes the "plain ADMIN never reads another Sede's data" invariant
 * hold trivially. Per-endpoint Sede scoping is the intended end state once there's a real UI.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditRepository audit;
    private final CurrentUser currentUser;

    public AuditController(AuditRepository audit, CurrentUser currentUser) {
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @GetMapping("/item-status")
    public List<AuditItemStatusEntry> itemStatus(
            @RequestParam(required = false) Integer noteId,
            @RequestParam(required = false) Integer itemId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireSuperadmin();
        return audit.getItemStatusAudit(noteId, itemId, page, size);
    }

    @GetMapping("/login")
    public List<AuditLoginEntry> login(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireSuperadmin();
        return audit.getLoginAudit(username, page, size);
    }

    @GetMapping("/admin-actions")
    public List<AuditAdminActionEntry> adminActions(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireSuperadmin();
        return audit.getAdminActionAudit(actor, targetType, page, size);
    }

    private void requireSuperadmin() {
        CallerPrincipal caller = currentUser.require();
        if (!caller.isSuperadmin()) {
            throw ApiException.forbidden("PERMISSION_DENIED", "No tiene permiso para ver la auditoría.");
        }
    }
}
