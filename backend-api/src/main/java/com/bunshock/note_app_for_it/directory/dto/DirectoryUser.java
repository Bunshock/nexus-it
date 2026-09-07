package com.bunshock.note_app_for_it.directory.dto;

/**
 * One resolved directory account. The contract's §7.6 shape is
 * {@code {username, fullName, email, dni}}; {@code ou} (the account's
 * distinguished-name / OU path) is kept as a lossless extension — the desktop app's AD
 * user-selection popup displays it, so dropping it would be a UI regression at cutover.
 */
public record DirectoryUser(String username, String fullName, String email, String dni, String ou) {
}
