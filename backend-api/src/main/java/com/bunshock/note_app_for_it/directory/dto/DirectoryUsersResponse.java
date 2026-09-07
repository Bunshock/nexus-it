package com.bunshock.note_app_for_it.directory.dto;

import java.util.List;

/** {@code GET /api/v1/directory/users} body — {@code {results: [...]}}, empty (not 404) on no match. */
public record DirectoryUsersResponse(List<DirectoryUser> results) {
}
