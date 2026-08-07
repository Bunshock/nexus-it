package com.bunshock.note_app_for_it_frontend;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.ChangelogEntry;
import com.bunshock.note_app_for_it_frontend.models.UpdateInfo;
import com.bunshock.note_app_for_it_frontend.services.NetworkShareUpdateService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// isNewer() is package-private static and copyAndVerifyInstaller() is package-private instance —
// both tested via reflection, same convention AdApiServiceTest already uses for its own small
// static helpers, since every *ServiceTest in this suite sits flat in this package rather than
// mirrored under .services.
class NetworkShareUpdateServiceTest {

    private final NetworkShareUpdateService service = NetworkShareUpdateService.getInstance();

    @AfterEach
    void resetConfiguration() {
        service.configure(null);
    }

    private boolean isNewer(String remote, String current) throws Exception {
        Method m = NetworkShareUpdateService.class.getDeclaredMethod("isNewer", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, remote, current);
    }

    private Path copyAndVerifyInstaller(UpdateInfo info) throws Exception {
        Method m = NetworkShareUpdateService.class.getDeclaredMethod("copyAndVerifyInstaller", UpdateInfo.class);
        m.setAccessible(true);
        try {
            return (Path) m.invoke(service, info);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IOException ioe) throw ioe;
            throw e;
        }
    }

    @Test
    void isNewerHandlesDifferentSegmentCounts() throws Exception {
        // "1.10.0" is genuinely newer than "1.9.0" — a plain String comparison would get this
        // backwards, which is the whole reason isNewer() exists instead of remote.compareTo(current).
        assertTrue(isNewer("1.10.0", "1.9.0"));
        assertFalse(isNewer("1.9.0", "1.10.0"));
    }

    @Test
    void isNewerReturnsFalseForEqualVersions() throws Exception {
        assertFalse(isNewer("1.2.0", "1.2.0"));
    }

    @Test
    void isNewerHandlesMissingTrailingSegments() throws Exception {
        // "1.2" vs "1.2.0" — missing segments default to 0, so these compare equal, not "newer".
        assertFalse(isNewer("1.2", "1.2.0"));
        assertTrue(isNewer("1.3", "1.2.9"));
    }

    @Test
    void isNewerReturnsFalseForBlankOrNullInputs() throws Exception {
        assertFalse(isNewer(null, "1.0.0"));
        assertFalse(isNewer("1.0.0", null));
        assertFalse(isNewer("", "1.0.0"));
    }

    @Test
    void checkForUpdateReturnsEmptyWhenNotConfigured() {
        service.configure(null);
        assertFalse(service.isConfigured());
        assertTrue(service.checkForUpdate("1.0.0").isEmpty());

        service.configure("   ");
        assertFalse(service.isConfigured());
        assertTrue(service.checkForUpdate("1.0.0").isEmpty());
    }

    @Test
    void checkForUpdateReturnsEmptyWhenManifestFileIsMissing(@TempDir Path tempDir) {
        // Fails safe rather than throwing — same convention every other external-dependency
        // check in this app follows (AD/GLPI/SMTP/remote DB).
        service.configure(tempDir.resolve("does-not-exist.json").toString());
        assertTrue(service.checkForUpdate("1.0.0").isEmpty());
    }

    @Test
    void checkForUpdatePresentWhenManifestVersionIsNewer(@TempDir Path tempDir) throws IOException {
        Path manifest = tempDir.resolve("latest.json");
        Files.writeString(manifest, """
            {
              "version": "9.9.9",
              "installerFileName": "GeneradorDeNotasIT-9.9.9.exe",
              "fileSizeBytes": 123456,
              "notes": "Nota de prueba"
            }
            """);
        service.configure(manifest.toString());

        Optional<UpdateInfo> result = service.checkForUpdate("1.0.0");
        assertTrue(result.isPresent());
        assertEquals("9.9.9", result.get().getVersion());
        assertEquals("GeneradorDeNotasIT-9.9.9.exe", result.get().getInstallerFileName());
        assertEquals(123456L, result.get().getFileSizeBytes());
        assertEquals("Nota de prueba", result.get().getNotes());
    }

    @Test
    void checkForUpdateEmptyWhenManifestVersionIsNotNewer(@TempDir Path tempDir) throws IOException {
        Path manifest = tempDir.resolve("latest.json");
        Files.writeString(manifest, """
            { "version": "1.0.0", "installerFileName": "x.exe", "fileSizeBytes": 1, "notes": "" }
            """);
        service.configure(manifest.toString());

        assertTrue(service.checkForUpdate("1.0.0").isEmpty());
        assertTrue(service.checkForUpdate("2.0.0").isEmpty());
    }

    @Test
    void getChangelogReturnsEmptyListWhenNotConfiguredOrFileMissing(@TempDir Path tempDir) {
        service.configure(null);
        assertTrue(service.getChangelog().isEmpty());

        service.configure(tempDir.resolve("latest.json").toString());
        assertTrue(service.getChangelog().isEmpty());
    }

    @Test
    void getChangelogReadsFullHistoryFromSiblingFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("changelog.json"), """
            [
              { "version": "1.2.0", "date": "2026-08-01", "notes": "Segunda nota" },
              { "version": "1.1.0", "date": "2026-07-01", "notes": "Primera nota" }
            ]
            """);
        // checkForUpdate/getChangelog both derive their file from manifestPath's sibling
        // directory — the manifest file itself doesn't need to exist for getChangelog().
        service.configure(tempDir.resolve("latest.json").toString());

        List<ChangelogEntry> entries = service.getChangelog();
        assertEquals(2, entries.size());
        assertEquals("1.2.0", entries.get(0).getVersion());
        assertEquals("2026-08-01", entries.get(0).getDate());
        assertEquals("Segunda nota", entries.get(0).getNotes());
        assertEquals("1.1.0", entries.get(1).getVersion());
    }

    @Test
    void copyAndVerifyInstallerThrowsWhenNotConfigured() {
        service.configure(null);
        UpdateInfo info = new UpdateInfo("1.0.0", "x.exe", 10, "");
        assertThrows(IOException.class, () -> copyAndVerifyInstaller(info));
    }

    @Test
    void copyAndVerifyInstallerGivesAClearReasonWhenTheInstallerFileIsMissing(@TempDir Path tempDir) {
        // Direct user report: the error dialog only ever showed a bare file path, with no
        // indication of *why* the copy failed — this locks in that a missing source file gets an
        // explicit, actionable Spanish message instead of NoSuchFileException's raw path-only one.
        service.configure(tempDir.resolve("latest.json").toString());
        UpdateInfo info = new UpdateInfo("1.0.0", "does-not-exist.exe", 10, "");

        IOException ex = assertThrows(IOException.class, () -> copyAndVerifyInstaller(info));
        assertTrue(ex.getMessage().contains("No se encontró el instalador"),
            "expected a clear 'installer not found' reason, got: " + ex.getMessage());
    }

    @Test
    void copyAndVerifyInstallerCopiesFileWhenSizeMatches(@TempDir Path tempDir) throws Exception {
        byte[] content = "contenido de prueba del instalador".getBytes();
        String installerName = "test-installer-" + System.nanoTime() + ".exe";
        Files.write(tempDir.resolve(installerName), content);
        service.configure(tempDir.resolve("latest.json").toString());

        UpdateInfo info = new UpdateInfo("1.0.0", installerName, content.length, "");
        Path copied = null;
        try {
            copied = copyAndVerifyInstaller(info);
            assertTrue(Files.exists(copied));
            assertEquals(content.length, Files.size(copied));
        } finally {
            if (copied != null) Files.deleteIfExists(copied);
        }
    }

    @Test
    void copyAndVerifyInstallerThrowsAndDeletesFileWhenSizeMismatch(@TempDir Path tempDir) throws Exception {
        byte[] content = "contenido real".getBytes();
        String installerName = "mismatch-installer-" + System.nanoTime() + ".exe";
        Files.write(tempDir.resolve(installerName), content);
        service.configure(tempDir.resolve("latest.json").toString());

        // Manifest claims a size the actual copied file doesn't match — simulates a truncated
        // network copy, the exact scenario this size check exists to catch.
        UpdateInfo info = new UpdateInfo("1.0.0", installerName, content.length + 999, "");
        assertThrows(IOException.class, () -> copyAndVerifyInstaller(info));

        Path tempSubfolder = Path.of(System.getProperty("java.io.tmpdir"), "notas-it-update");
        assertFalse(Files.exists(tempSubfolder.resolve(installerName)),
            "the partially-copied file must be deleted, not left behind, on a size mismatch");
    }
}
