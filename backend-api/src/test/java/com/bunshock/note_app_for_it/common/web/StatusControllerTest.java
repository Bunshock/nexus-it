package com.bunshock.note_app_for_it.common.web;

import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.directory.AdApiClient;
import com.bunshock.note_app_for_it.directory.DirectoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Plain unit test — {@link StatusController} takes its collaborators in the constructor, so no
 * Spring context. The DB probe is mocked; the directory probe uses a scriptable {@link AdApiClient}
 * stub (same pattern as {@code DirectoryServiceTest.FakeAdApiClient}).
 */
@ExtendWith(MockitoExtension.class)
class StatusControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    private final DirectoryProperties directoryProperties = new DirectoryProperties();
    private final StubAdApiClient adApiClient = new StubAdApiClient();
    private StatusController controller;

    @BeforeEach
    void setUp() {
        controller = new StatusController(jdbc, adApiClient, directoryProperties, new CurrentUser());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CallerPrincipal("tester", "USER", 1), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void middlewareAlwaysUp_databaseUp_directoryNotConfiguredWhenPropsBlank() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        StatusResponse r = controller.status();

        assertEquals(ComponentStatus.UP, r.middleware());
        assertEquals(ComponentStatus.UP, r.database());
        assertEquals(ComponentStatus.NOT_CONFIGURED, r.directory());
        assertFalse(adApiClient.called, "directory must not be probed when unconfigured");
    }

    @Test
    void databaseDownWhenProbeThrows() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new RuntimeException("connection refused"));

        assertEquals(ComponentStatus.DOWN, controller.status().database());
    }

    @Test
    void directoryUpWhenConfiguredAndClientAnswers() {
        directoryProperties.setBaseUrl("http://ad-api:8099");
        directoryProperties.setToken("svc-token");
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        adApiClient.responder = () -> List.of();

        StatusResponse r = controller.status();

        assertTrue(adApiClient.called);
        assertEquals(ComponentStatus.UP, r.directory());
    }

    @Test
    void directoryDownWhenConfiguredAndClientThrows() {
        directoryProperties.setBaseUrl("http://ad-api:8099");
        directoryProperties.setToken("svc-token");
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        adApiClient.responder = () -> {
            throw new com.bunshock.note_app_for_it.common.web.ApiException(
                    HttpStatus.BAD_GATEWAY, "DIRECTORY_UNAVAILABLE", "no route to host");
        };

        assertEquals(ComponentStatus.DOWN, controller.status().directory());
    }

    private static final class StubAdApiClient implements AdApiClient {
        boolean called;
        java.util.function.Supplier<List<AdApiUser>> responder = List::of;

        @Override
        public List<AdApiUser> fetch(String dni, String name, String username) {
            called = true;
            return responder.get();
        }
    }
}
