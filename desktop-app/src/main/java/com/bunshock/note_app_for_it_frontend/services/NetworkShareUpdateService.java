package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.update.ChangelogEntry;
import com.bunshock.note_app_for_it_frontend.models.update.UpdateInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the update manifest (latest.json) and changelog (changelog.json) from a UNC network
 * share, per CLAUDE.md's "Auto-update system" design — chosen over a real API for now, since it
 * needs no new auth/availability dependency (a future ApiUpdateService can implement the same
 * IUpdateService interface later, same "mock → real is a one-class swap" pattern as every other
 * service in this app).
 */
public class NetworkShareUpdateService implements IUpdateService {

    private static final String CHANGELOG_FILE_NAME = "changelog.json";
    private static final String HELPER_SCRIPT_RESOURCE =
        "/com/bunshock/note_app_for_it_frontend/scripts/update-helper.bat";
    private static final String TEMP_SUBFOLDER = "notas-it-update";

    private static NetworkShareUpdateService instance;

    private String manifestPath;
    private final ObjectMapper mapper = new ObjectMapper();

    private NetworkShareUpdateService() {}

    public static NetworkShareUpdateService getInstance() {
        if (instance == null) instance = new NetworkShareUpdateService();
        return instance;
    }

    public void configure(String manifestPath) {
        this.manifestPath = manifestPath;
    }

    @Override
    public boolean isConfigured() {
        return manifestPath != null && !manifestPath.isBlank();
    }

    @Override
    public Optional<UpdateInfo> checkForUpdate(String currentVersion) {
        if (!isConfigured()) return Optional.empty();
        try {
            String json = Files.readString(Paths.get(manifestPath));
            LatestManifestDto dto = mapper.readValue(json, LatestManifestDto.class);
            if (dto.version == null || dto.version.isBlank()) return Optional.empty();
            if (!isNewer(dto.version, currentVersion)) return Optional.empty();
            return Optional.of(new UpdateInfo(dto.version, dto.installerFileName, dto.fileSizeBytes, dto.notes));
        } catch (Exception e) {
            // Network share unreachable, missing/malformed manifest, etc. — fail safe, same
            // degrade-gracefully convention every other external dependency in this app follows
            // (AD/GLPI/SMTP/remote DB). Never surfaces as a crash or a blocked UI action.
            return Optional.empty();
        }
    }

    @Override
    public List<ChangelogEntry> getChangelog() {
        if (!isConfigured()) return List.of();
        try {
            Path changelogPath = Paths.get(manifestPath).resolveSibling(CHANGELOG_FILE_NAME);
            String json = Files.readString(changelogPath);
            List<ChangelogEntryDto> dtos =
                mapper.readValue(json, new TypeReference<List<ChangelogEntryDto>>() {});
            return dtos.stream()
                .map(d -> new ChangelogEntry(d.version, d.date, d.notes))
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void downloadAndInstall(UpdateInfo info) throws IOException {
        Path installerPath = copyAndVerifyInstaller(info);
        Path helperPath = extractHelperScript();
        launchHelperAndRelaunch(installerPath, helperPath);
    }

    /** The deterministic, non-process-spawning part of downloadAndInstall() — split out
     * specifically so it can be unit-tested without launching a real Windows process or a real
     * installer. See NetworkShareUpdateServiceTest.
     *
     * Every failure branch below rewrites the raw IOException into an explicit, Spanish "why" —
     * direct user feedback that the original bare message (often just the file path, e.g. what
     * NoSuchFileException.getMessage() returns) left them unable to tell a missing installer file
     * apart from an unreachable network share or a genuinely failed copy. Deliberately no path is
     * ever included in the rewritten message, and the raw underlying exception message is dropped
     * rather than appended — for a path-related IOException (NoSuchFileException, a failed
     * Files.copy, a failed ProcessBuilder.start()) that raw message typically *is* the file path,
     * so appending it would defeat the point. The share path is itself effectively public — it
     * sits in every technician's own app-config.json — but a popup is far more likely to be
     * screenshotted/shared than a config file, so keeping it out of the popup still cuts down
     * exposure even though it isn't a real access-control boundary. */
    Path copyAndVerifyInstaller(UpdateInfo info) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Auto-actualización no configurada (updates.manifestPath vacío)");
        }
        Path sourcePath = Paths.get(manifestPath).resolveSibling(info.getInstallerFileName());
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_SUBFOLDER);
        Files.createDirectories(tempDir);
        Path destPath = tempDir.resolve(info.getInstallerFileName());

        try {
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.NoSuchFileException e) {
            // On a UNC path this also covers "the whole share/server is unreachable" — Windows'
            // own path resolution can't distinguish that from "file genuinely missing" at this
            // API level, so the message covers both rather than falsely claiming certainty.
            throw new IOException("No se encontró el instalador en el servidor de actualizaciones. "
                + "Verifique que el archivo exista y que el servidor esté accesible.", e);
        } catch (IOException e) {
            throw new IOException("No se pudo copiar el instalador desde el servidor de "
                + "actualizaciones — verifique la conexión de red.", e);
        }

        long actualSize = Files.size(destPath);
        if (actualSize != info.getFileSizeBytes()) {
            Files.deleteIfExists(destPath);
            throw new IOException("La copia del instalador quedó incompleta (" + actualSize
                + " bytes, se esperaban " + info.getFileSizeBytes() + " bytes) — reintentar.");
        }
        return destPath;
    }

    private Path extractHelperScript() throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_SUBFOLDER);
        Files.createDirectories(tempDir);
        Path destPath = tempDir.resolve("update-helper.bat");
        try (InputStream in = getClass().getResourceAsStream(HELPER_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("No se encontró el recurso interno update-helper.bat "
                    + "— la aplicación puede estar empaquetada incorrectamente.");
            }
            Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("No se pudo preparar el instalador en el equipo local. "
                + "Verifique espacio en disco y permisos.", e);
        }
        return destPath;
    }

    /** Launches update-helper.bat as its own, undetached OS process and returns immediately —
     * deliberately not waited on, since the caller closes this app (Platform.exit()) right after
     * this returns, and a process this app has already exited can't be waited on by it anyway.
     * ProcessHandle.current().info().command() is meant to resolve to the app's own running
     * executable, but this is NOT YET VERIFIED against a real jpackage-built .exe — only ever run
     * so far under `mvn javafx:run`, where it resolves to a java/javaw launcher, not the eventual
     * packaged app. Confirm once packaging (CLAUDE.md's build/packaging section) actually exists —
     * same "verify once against a real packaged build" caveat the rest of this design carries. */
    private void launchHelperAndRelaunch(Path installerPath, Path helperPath) throws IOException {
        String appExePath = ProcessHandle.current().info().command().orElse("");
        ProcessBuilder pb = new ProcessBuilder(
            helperPath.toString(), installerPath.toString(), appExePath);
        try {
            pb.start();
        } catch (IOException e) {
            throw new IOException("No se pudo iniciar el proceso de instalación.", e);
        }
    }

    /** Numeric, dot-separated version comparison — plain string comparison sorts "1.10.0" before
     * "1.9.0", so this can't just be a String.compareTo(). Package-private, tested via reflection
     * (this codebase's convention for small static helpers, e.g. AdApiService's
     * containsExactUsernameMatch()) rather than made public API. */
    static boolean isNewer(String remote, String current) {
        if (remote == null || remote.isBlank()) return false;
        if (current == null || current.isBlank()) return false;
        String[] r = remote.split("\\.");
        String[] c = current.split("\\.");
        for (int i = 0; i < Math.max(r.length, c.length); i++) {
            int rv = parsePart(r, i);
            int cv = parsePart(c, i);
            if (rv != cv) return rv > cv;
        }
        return false;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class LatestManifestDto {
        public String version;
        public String installerFileName;
        public long fileSizeBytes;
        public String notes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChangelogEntryDto {
        public String version;
        public String date;
        public String notes;
    }
}
