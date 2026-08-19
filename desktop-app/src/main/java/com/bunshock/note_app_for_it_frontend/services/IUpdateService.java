package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.update.ChangelogEntry;
import com.bunshock.note_app_for_it_frontend.models.update.UpdateInfo;

/**
 * Auto-update checks, per CLAUDE.md's "Auto-update system" design — a Strategy interface so a
 * future API-backed implementation can swap in later as a one-class change, same "mock → real"
 * pattern as IADService/IEquipmentService/etc.
 */
public interface IUpdateService {

    /** Reads the manifest and compares against currentVersion. Empty when unconfigured,
     * unreachable, or no newer version exists — every failure mode fails safe, never throws. */
    Optional<UpdateInfo> checkForUpdate(String currentVersion);

    /** Full release-note history, newest first. Empty list (never throws) when unconfigured or
     * unreadable — this is a "nice to have" view, not a gate on anything. */
    List<ChangelogEntry> getChangelog();

    /** Copies the installer locally, verifies its size against the manifest, and launches the
     * detached relaunch helper. Throws on a real failure (share unreachable mid-copy, truncated
     * download) — unlike the two read methods above, a caller-confirmed install must not fail
     * silently. Does not itself close the app; the caller does that only after this returns
     * without error. */
    void downloadAndInstall(UpdateInfo info) throws IOException;

    default boolean isConfigured() { return true; }
}
