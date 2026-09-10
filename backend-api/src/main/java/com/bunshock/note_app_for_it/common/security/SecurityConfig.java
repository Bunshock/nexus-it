package com.bunshock.note_app_for_it.common.security;

import com.bunshock.note_app_for_it.common.web.GlobalExceptionHandler.ErrorBody;
import com.bunshock.note_app_for_it.common.web.GlobalExceptionHandler.ErrorPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

/**
 * Stateless bearer-token API — backend-contract.md §2. No form login, no CSRF (no
 * browser session/cookie to forge), authentication is entirely
 * {@link SessionAuthenticationFilter} resolving the opaque session token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // A plain, locally-owned instance rather than the app's autoconfigured ObjectMapper bean —
    // this only ever serializes the small, fixed ErrorBody shape, so it doesn't need whatever
    // modules/config the main app's Jackson bean might carry, and avoids coupling this security
    // filter chain's bean graph to Jackson autoconfiguration timing/module wiring.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/config",
                                "/api/v1/auth/login",
                                // dev profile only — DevAuthController is @Profile("dev"),
                                // so this route simply doesn't exist in a prod build.
                                "/api/v1/auth/dev-login",
                                "/api/v1/health",
                                // OpenAPI/Swagger UI — permitAll for now (dev convenience); revisit
                                // before a real deployment if the API shape shouldn't be public.
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, 401, "UNAUTHENTICATED", "Falta o expiró la sesión."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "FORBIDDEN", "No tiene permiso para esta acción.")))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response,
            int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorBody(new ErrorPayload(code, message, Map.of()))));
    }
}
