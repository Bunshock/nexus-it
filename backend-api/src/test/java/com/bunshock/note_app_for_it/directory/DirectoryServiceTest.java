package com.bunshock.note_app_for_it.directory;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.directory.AdApiClient.AdApiUser;
import com.bunshock.note_app_for_it.directory.dto.DirectoryUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit test — {@link DirectoryService} takes its {@link AdApiClient} + {@link
 * DirectoryProperties} in the constructor, so no HTTP and no Spring context. Exercises the
 * ported {@code AdApiService.search()} logic: DNI/name query fan-out, merge-by-samAccountName,
 * word fallback, and the client-side AND re-verification.
 */
class DirectoryServiceTest {

    /** Records every {@code fetch(dni, name, username)} and answers via a scriptable responder. */
    static class FakeAdApiClient implements AdApiClient {
        final List<String[]> calls = new ArrayList<>();
        Function<String[], List<AdApiUser>> responder = key -> List.of();

        @Override
        public List<AdApiUser> fetch(String dni, String name, String username) {
            calls.add(new String[] { dni, name, username });
            return responder.apply(new String[] { dni, name, username });
        }

        boolean queriedName(String name) {
            return calls.stream().anyMatch(c -> name == null ? c[1] == null : name.equals(c[1]));
        }

        boolean queriedDni(String dni) {
            return calls.stream().anyMatch(c -> dni.equals(c[0]));
        }
    }

    private static DirectoryProperties configured() {
        DirectoryProperties p = new DirectoryProperties();
        p.setBaseUrl("http://ad.test");
        p.setToken("service-token");
        return p;
    }

    private static AdApiUser user(String sam, String display, String dni, String mail, String ou) {
        return new AdApiUser(sam, display, dni, mail, ou);
    }

    @Test
    void throwsServiceUnavailableWhenTheDirectoryIsNotConfigured() {
        FakeAdApiClient fake = new FakeAdApiClient();
        DirectoryService service = new DirectoryService(fake, new DirectoryProperties());

        ApiException ex = assertThrows(ApiException.class, () -> service.search("123", null, null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIRECTORY_NOT_CONFIGURED", ex.getCode());
        assertTrue(fake.calls.isEmpty(), "must not hit the directory when unconfigured");
    }

    @Test
    void returnsEmptyAndNeverCallsTheDirectoryWhenNoCriteriaGiven() {
        FakeAdApiClient fake = new FakeAdApiClient();
        DirectoryService service = new DirectoryService(fake, configured());

        assertTrue(service.search(null, null, null).isEmpty());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void resolvesAnExactNameInOneRoundAndNormalizesTheResult() {
        FakeAdApiClient fake = new FakeAdApiClient();
        fake.responder = k -> List.of(user("jrodriguez", "Rodriguez, Joaquin", "45.933.368", "j@x.com", "OU=Cau"));
        DirectoryService service = new DirectoryService(fake, configured());

        List<DirectoryUser> results = service.search(null, "Joaquin Rodriguez", null);

        assertEquals(1, results.size());
        DirectoryUser u = results.get(0);
        assertEquals("jrodriguez", u.username());
        assertEquals("Rodriguez Joaquin", u.fullName(), "comma stripped, spaces collapsed");
        assertEquals("45933368", u.dni(), "thousands-separator dots stripped");
        assertEquals("j@x.com", u.email());
        assertEquals("OU=Cau", u.ou());
        assertFalse(fake.queriedName("joaquin"), "no word-fallback round when the precise name matched");
        assertFalse(fake.queriedName("rodriguez"));
    }

    @Test
    void dropsACandidateThatMatchesTheDniButNotTheSuppliedName() {
        FakeAdApiClient fake = new FakeAdApiClient();
        // Simulates the real API ignoring the name and returning a DNI-only match.
        fake.responder = k -> List.of(user("bbuilder", "Builder, Bob", "12345678", "b@x.com", null));
        DirectoryService service = new DirectoryService(fake, configured());

        List<DirectoryUser> results = service.search("12345678", "Alice Wonder", null);

        assertTrue(results.isEmpty(), "name re-verification must exclude a DNI-only match");
    }

    @Test
    void mergesTheSamePersonAcrossVariantsPreferringTheMoreCompleteRecord() {
        FakeAdApiClient fake = new FakeAdApiClient();
        fake.responder = k -> {
            String name = k[1];
            if ("smith, alice".equals(name)) {
                return List.of(user("asmith", "Smith, Alice", "11222333", "a@x.com", "OU=X"));
            }
            return List.of(user("asmith", "Smith, Alice", null, null, null));
        };
        DirectoryService service = new DirectoryService(fake, configured());

        List<DirectoryUser> results = service.search(null, "Alice Smith", null);

        assertEquals(1, results.size());
        assertEquals("11222333", results.get(0).dni(), "the fuller record wins the merge");
        assertEquals("a@x.com", results.get(0).email());
    }

    @Test
    void runsTheWordFallbackRoundOnlyAfterThePreciseVariantsFindNothing() {
        FakeAdApiClient fake = new FakeAdApiClient();
        fake.responder = k -> "rodrig".equals(k[1])
                ? List.of(user("jrodriguez", "Rodriguez, Joaquin", "45933368", "j@x.com", "OU=Cau"))
                : List.of();
        DirectoryService service = new DirectoryService(fake, configured());

        List<DirectoryUser> results = service.search(null, "Joaquin Rodrig", null);

        assertEquals(1, results.size());
        assertEquals("jrodriguez", results.get(0).username());
        assertTrue(fake.queriedName("rodrig, joaquin"), "precise comma-reordered variant tried first");
        assertTrue(fake.queriedName("rodrig"), "then the per-word fallback");
    }

    @Test
    void queriesBothThePlainAndDottedFormsOfADni() {
        FakeAdApiClient fake = new FakeAdApiClient();
        DirectoryService service = new DirectoryService(fake, configured());

        service.search("45933368", null, null);

        assertTrue(fake.queriedDni("45933368"));
        assertTrue(fake.queriedDni("45.933.368"));
    }

    @Test
    void usernameReVerificationIsSeparatorAgnostic() {
        FakeAdApiClient fake = new FakeAdApiClient();
        fake.responder = k -> List.of(user("juan-perez", "Perez, Juan", "99887766", "jp@x.com", null));
        DirectoryService service = new DirectoryService(fake, configured());

        List<DirectoryUser> results = service.search(null, null, "juan.perez");

        assertEquals(1, results.size(), "'juan.perez' typed must still match stored 'juan-perez'");
        assertEquals("juan-perez", results.get(0).username());
    }

    // ── findExactByUsername (login-time profile snapshot) ────────────────────

    @Test
    void findExactByUsernameReturnsOnlyTheCaseInsensitiveExactAccount() {
        FakeAdApiClient fake = new FakeAdApiClient();
        // The loose API returns the real account plus a substring neighbour.
        fake.responder = k -> List.of(
                user("jperez", "Perez, Juan", "45933368", "jp@x.com", "OU=Cau"),
                user("jpereznandez", "Pereznandez, Jose", "11111111", "jn@x.com", null));
        DirectoryService service = new DirectoryService(fake, configured());

        var resolved = service.findExactByUsername("JPEREZ");

        assertTrue(resolved.isPresent());
        assertEquals("jperez", resolved.get().username());
        assertEquals("Perez Juan", resolved.get().fullName());
        assertEquals("45933368", resolved.get().dni());
    }

    @Test
    void findExactByUsernameIsEmptyWhenOnlySubstringMatchesComeBack() {
        FakeAdApiClient fake = new FakeAdApiClient();
        fake.responder = k -> List.of(user("jpereznandez", "Pereznandez, Jose", "11111111", "jn@x.com", null));
        DirectoryService service = new DirectoryService(fake, configured());

        assertTrue(service.findExactByUsername("jperez").isEmpty(),
                "a substring hit must never be mistaken for the caller's own account");
    }

    @Test
    void findExactByUsernameIsEmptyForBlankAndNeverHitsTheDirectory() {
        FakeAdApiClient fake = new FakeAdApiClient();
        DirectoryService service = new DirectoryService(fake, configured());

        assertTrue(service.findExactByUsername("  ").isEmpty());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void findExactByUsernamePropagatesTheDirectoryUnavailableError() {
        // AuthController relies on this being an ApiException it can catch to degrade gracefully.
        DirectoryService service = new DirectoryService(new FakeAdApiClient(), new DirectoryProperties());

        ApiException ex = assertThrows(ApiException.class, () -> service.findExactByUsername("jperez"));
        assertEquals("DIRECTORY_NOT_CONFIGURED", ex.getCode());
    }
}
