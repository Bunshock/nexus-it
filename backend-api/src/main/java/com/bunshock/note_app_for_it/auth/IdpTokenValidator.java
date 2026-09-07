package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Validates the IdP-issued access token presented to {@code POST /auth/login} (§2.1
 * step 5) — signature via the issuer's JWKS, standard {@code iss}/{@code aud}/{@code exp}
 * checks, all via {@link JwtDecoders#fromIssuerLocation}. Built lazily, not as an eager
 * Spring bean: {@code issuer-uri} is blank until Keycloak is actually reachable (a real
 * deployment prerequisite, not code — see the approved plan), and eagerly resolving OIDC
 * discovery at application startup would crash boot before that's true.
 */
@Component
public class IdpTokenValidator {

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
                        local = JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
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
}
