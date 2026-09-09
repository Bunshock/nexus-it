package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates the IdP-issued access token presented to {@code POST /auth/login} (§2.1
 * step 5) — signature via the issuer's JWKS, plus the {@code iss} / {@code exp} / {@code aud}
 * claims. Built lazily, not as an eager Spring bean: {@code issuer-uri} is blank until
 * Keycloak is actually reachable (a deployment prerequisite, not code — see the approved
 * plan), and eagerly resolving OIDC discovery at application startup would crash boot
 * before that's true.
 *
 * <p>The {@code aud} check binds a token to this middleware's own client: a token minted
 * for a <em>different</em> client in the same realm (correct issuer, valid signature, live
 * user) is rejected with {@code 401 INVALID_IDP_TOKEN}. It is only enforced when
 * {@code middleware.idp.client-id} is set — a blank client-id skips the check and logs a
 * warning, matching the "blank config value = that check is off" convention already used for
 * {@code allowed-group-name}. Every real deployment sets {@code client-id} (the desktop app
 * needs it from {@code GET /auth/config} to obtain a token at all), so in practice the check
 * is always on.
 */
@Component
public class IdpTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(IdpTokenValidator.class);

    private final IdpProperties properties;
    private volatile JwtDecoder decoder;

    public IdpTokenValidator(IdpProperties properties) {
        this.properties = properties;
    }

    public Jwt validate(String bearerToken) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDP_NOT_CONFIGURED",
                    "El proveedor de identidad no está configurado todavía.");
        }
        try {
            return decoder().decode(bearerToken);
        } catch (JwtException e) {
            throw ApiException.unauthorized("INVALID_IDP_TOKEN", "El token de identidad no es válido.");
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                local = decoder;
                if (local == null) {
                    try {
                        local = buildDecoder(properties.getIssuerUri());
                    } catch (RuntimeException e) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDP_UNREACHABLE",
                                "No se pudo contactar al proveedor de identidad.");
                    }
                    decoder = local;
                }
            }
        }
        return local;
    }

    /**
     * {@link JwtDecoders#fromIssuerLocation} wires only the default {@code iss}/{@code exp}
     * chain; {@link NimbusJwtDecoder#setJwtValidator} replaces that chain wholesale, so the
     * issuer + timestamp validators are re-added alongside the audience one.
     */
    private JwtDecoder buildDecoder(String issuer) {
        NimbusJwtDecoder nimbus = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        String clientId = properties.getClientId();
        if (clientId != null && !clientId.isBlank()) {
            nimbus.setJwtValidator(JwtValidators.createDefaultWithValidators(
                    new JwtIssuerValidator(issuer), audienceValidator(clientId)));
        } else {
            log.warn("middleware.idp.client-id is blank — the IdP token 'aud' claim is NOT being "
                    + "validated. A token minted for another realm client will be accepted. Set "
                    + "middleware.idp.client-id to enforce the audience check.");
            nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        }
        return nimbus;
    }

    /**
     * Passes only when the token's {@code aud} claim contains {@code expectedAudience}
     * (Keycloak stamps {@code aud: <client-id>} via the client's audience mapper).
     * Package-private + static so it can be unit-tested directly against synthetic
     * {@link Jwt}s, with no OIDC discovery or Spring context.
     */
    static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(expectedAudience));
    }
}
