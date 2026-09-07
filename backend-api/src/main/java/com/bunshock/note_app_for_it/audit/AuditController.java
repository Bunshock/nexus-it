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
 * history — see the "Audit logging and SUPERADMIN role" / "Audit trail" sections of its
 * CLAUDE.md), so this gates on role directly (`ADMIN`/`SUPERADMIN`) rather than a permission.
 * **Not Sede-scoped in v1** — any admin sees every Sede's audit rows; revisit if that turns out
 * to matter once there's a real audit-reading UI to try it against.
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
        requireAdmin();
        return audit.getItemStatusAudit(noteId, itemId, page, size);
    }

    @GetMapping("/login")
    public List<AuditLoginEntry> login(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireAdmin();
        return audit.getLoginAudit(username, page, size);
    }

    @GetMapping("/admin-actions")
    public List<AuditAdminActionEntry> adminActions(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireAdmin();
        return audit.getAdminActionAudit(actor, targetType, page, size);
    }

    private void requireAdmin() {
        CallerPrincipal caller = currentUser.require();
        if (!"ADMIN".equals(caller.role()) && !"SUPERADMIN".equals(caller.role())) {
            throw ApiException.forbidden("PERMISSION_DENIED", "No tiene permiso para ver la auditoría.");
        }
    }
}
