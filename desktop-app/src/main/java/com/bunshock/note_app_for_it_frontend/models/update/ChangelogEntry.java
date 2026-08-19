package com.bunshock.note_app_for_it_frontend.models.update;


/** One past release's notes, read from changelog.json on the update network share — see
 * IUpdateService.getChangelog(). Full history, independent of whether that version is still
 * "the latest" (unlike UpdateInfo, which only ever describes the newest one). */
public class ChangelogEntry {
    private final String version;
    private final String date;
    private final String notes;

    public ChangelogEntry(String version, String date, String notes) {
        this.version = version;
        this.date = date;
        this.notes = notes;
    }

    public String getVersion() { return version; }
    public String getDate() { return date; }
    public String getNotes() { return notes; }
}
