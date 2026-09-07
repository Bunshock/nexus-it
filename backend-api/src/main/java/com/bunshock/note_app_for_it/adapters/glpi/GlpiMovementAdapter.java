package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.port.MovementPort;
import com.bunshock.note_app_for_it.port.dto.MovementCommand;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * GLPI implementation of {@link MovementPort} — <b>skeleton, not implemented</b>. Will
 * {@link GlpiUserTokenResolver#resolveFor resolve} the acting technician's {@code user_token},
 * open a GLPI session as them ({@link GlpiClient}), {@code PUT /<itemtype>/{id}} with the
 * {@code states_id} from {@link GlpiAdapterProperties#syncTargetFor}, the {@code users_id}
 * (holder), and — for RELOCATE — the {@code locations_id}, then stamp
 * {@code motivodemovimientofield} / {@code comentariosdemovimientofield} with the note ref +
 * reason + acting tech.
 */
@Component
public class GlpiMovementAdapter implements MovementPort {

    private final GlpiClient client;
    private final GlpiUserTokenResolver userTokens;
    private final GlpiAdapterProperties properties;

    public GlpiMovementAdapter(GlpiClient client, GlpiUserTokenResolver userTokens,
            GlpiAdapterProperties properties) {
        this.client = client;
        this.userTokens = userTokens;
        this.properties = properties;
    }

    @Override
    public void applyMovement(MovementCommand command) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "El adaptador GLPI todavía no implementa: MovementPort.applyMovement");
    }
}
