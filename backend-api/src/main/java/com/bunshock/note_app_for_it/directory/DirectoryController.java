package com.bunshock.note_app_for_it.directory;

import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.directory.dto.DirectoryUsersResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * backend-contract.md §7.6 / D8 — replaces the desktop app's direct {@code IADService.search()}.
 * Any authenticated session; no permission, not Sede-scoped. AND-combined criteria, at least one
 * required. No match is {@code 200 {results: []}}, never {@code 404}; the directory being
 * unreachable is a {@code 502}.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private final DirectoryService directory;
    private final CurrentUser currentUser;

    public DirectoryController(DirectoryService directory, CurrentUser currentUser) {
        this.directory = directory;
        this.currentUser = currentUser;
    }

    @GetMapping("/users")
    public DirectoryUsersResponse users(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String dni,
            @RequestParam(required = false) String username) {
        currentUser.require();
        if (isBlank(name) && isBlank(dni) && isBlank(username)) {
            throw ApiException.badRequest("MISSING_SEARCH_CRITERIA",
                    "Especifique al menos un criterio de búsqueda (nombre, DNI o usuario).");
        }
        return new DirectoryUsersResponse(directory.search(dni, name, username));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
