package com.bunshock.note_app_for_it.port;

import com.bunshock.note_app_for_it.port.dto.MovementCommand;

/**
 * The single write into the external inventory system (backend-contract.md §4/§10). Called by
 * the write queue's worker, never synchronously from a controller. All-or-nothing per command;
 * a transient failure is retried, an unrecoverable conflict marks the step {@code FAILED}.
 */
public interface MovementPort {

    void applyMovement(MovementCommand command);
}
