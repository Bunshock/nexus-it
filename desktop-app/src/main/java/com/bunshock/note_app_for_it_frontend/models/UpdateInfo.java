package com.bunshock.note_app_for_it_frontend.models;

/** A newer-version manifest entry — see IUpdateService.checkForUpdate(). fileSizeBytes is a
 * plain byte-count sanity check against a truncated network copy, not a cryptographic guarantee. */
public class UpdateInfo {
    private final String version;
    private final String installerFileName;
    private final long fileSizeBytes;
    private final String notes;

    public UpdateInfo(String version, String installerFileName, long fileSizeBytes, String notes) {
        this.version = version;
        this.installerFileName = installerFileName;
        this.fileSizeBytes = fileSizeBytes;
        this.notes = notes;
    }

    public String getVersion() { return version; }
    public String getInstallerFileName() { return installerFileName; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public String getNotes() { return notes; }
}
