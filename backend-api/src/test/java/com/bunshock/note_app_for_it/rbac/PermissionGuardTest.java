package com.bunshock.note_app_for_it.rbac;

import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionGuardTest {

    @Mock
    private RolePermissionRepository rolePermissions;

    private PermissionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PermissionGuard(rolePermissions);
    }

    @Test
    void requireSucceedsWhenTheRoleHasThePermission() {
        when(rolePermissions.getPermissionsForRole("ADMIN"))
                .thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal caller = new CallerPrincipal("jperez", "ADMIN", 1);

        assertDoesNotThrow(() -> guard.require(caller, Permission.APPROVE_NOTES));
    }

    @Test
    void requireThrows403WhenTheRoleLacksThePermission_denyByDefault() {
        when(rolePermissions.getPermissionsForRole("USER")).thenReturn(EnumSet.noneOf(Permission.class));
        CallerPrincipal caller = new CallerPrincipal("jperez", "USER", 1);

        ApiException ex = assertThrows(ApiException.class,
                () -> guard.require(caller, Permission.APPROVE_NOTES));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void adminScopedToOwnSedeIsAllowedOnAMatchingNote() {
        when(rolePermissions.getPermissionsForRole("ADMIN")).thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal admin = new CallerPrincipal("jperez", "ADMIN", 3);

        assertDoesNotThrow(() -> guard.requireSedeScoped(admin, Permission.APPROVE_NOTES, 3));
    }

    @Test
    void adminScopedToOwnSedeIsDeniedOnAnotherSedesNote_H2() {
        when(rolePermissions.getPermissionsForRole("ADMIN")).thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal admin = new CallerPrincipal("jperez", "ADMIN", 3);

        ApiException ex = assertThrows(ApiException.class,
                () -> guard.requireSedeScoped(admin, Permission.APPROVE_NOTES, 99));
        assertEquals("SEDE_MISMATCH", ex.getCode());
    }

    @Test
    void superadminBypassesTheSedeFence_H3() {
        when(rolePermissions.getPermissionsForRole("SUPERADMIN")).thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal superadmin = new CallerPrincipal("mgarcia", "SUPERADMIN", 3);

        assertDoesNotThrow(() -> guard.requireSedeScoped(superadmin, Permission.APPROVE_NOTES, 99));
    }

    @Test
    void aNullTargetSedeNeverMatchesForAPlainAdmin_failSafe() {
        when(rolePermissions.getPermissionsForRole("ADMIN")).thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal admin = new CallerPrincipal("jperez", "ADMIN", 3);

        assertThrows(ApiException.class,
                () -> guard.requireSedeScoped(admin, Permission.APPROVE_NOTES, null));
    }

    @Test
    void anAdminWithNoSedeOfTheirOwnNeverMatchesEitherNoteSede_failSafe() {
        when(rolePermissions.getPermissionsForRole("ADMIN")).thenReturn(Set.of(Permission.APPROVE_NOTES));
        CallerPrincipal admin = new CallerPrincipal("jperez", "ADMIN", null);

        assertThrows(ApiException.class,
                () -> guard.requireSedeScoped(admin, Permission.APPROVE_NOTES, 3));
    }
}
