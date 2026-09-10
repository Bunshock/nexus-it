package com.bunshock.note_app_for_it.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the {@code aud} claim validator wired into {@link IdpTokenValidator} — no
 * OIDC discovery, no Spring context, just the predicate run against synthetic tokens. Guards
 * the "a valid same-realm token issued for another client must be rejected" requirement
 * ({@code NEXT-SESSION.md} gap #5, {@code keycloak-integration.md} §4a).
 */
class IdpTokenValidatorTest {

    private static final OAuth2TokenValidator<Jwt> VALIDATOR =
            IdpTokenValidator.audienceValidator("nexus-it");

    private static Jwt jwt(Object audClaim) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("test.user");
        if (audClaim != null) {
            b.claim(JwtClaimNames.AUD, audClaim);
        }
        return b.build();
    }

    @Test
    void acceptsATokenWhoseOnlyAudienceIsTheClientId() {
        assertFalse(VALIDATOR.validate(jwt(List.of("nexus-it"))).hasErrors());
    }

    @Test
    void acceptsWhenTheClientIdIsOneOfSeveralAudiences() {
        assertFalse(VALIDATOR.validate(jwt(List.of("account", "nexus-it"))).hasErrors());
    }

    @Test
    void rejectsATokenMintedForADifferentRealmClient() {
        OAuth2TokenValidatorResult result = VALIDATOR.validate(jwt(List.of("some-other-app")));
        assertTrue(result.hasErrors());
    }

    @Test
    void rejectsATokenThatCarriesNoAudienceClaim() {
        assertTrue(VALIDATOR.validate(jwt(null)).hasErrors());
    }
}
