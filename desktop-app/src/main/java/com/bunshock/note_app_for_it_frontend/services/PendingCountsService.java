package com.bunshock.note_app_for_it_frontend.services;

import java.util.ArrayList;
import java.util.List;

// Singleton pub/sub so scattered mutation points (a note being saved, a GLPI sync/reject action,
// a Préstamo return/lost action) can tell MainController its sidebar pending-count badges
// (Historial's GLPI-pending count, Préstamos' return-pending count) might be stale — same
// listener-registration shape as AdminSession's addOnActivateListener, just a single "something
// changed" event instead of activate/deactivate/expire. No state is cached here; recomputing the
// actual counts is MainController's job.
public class PendingCountsService {

    private static final PendingCountsService INSTANCE = new PendingCountsService();

    private final List<Runnable> onChangeListeners = new ArrayList<>();

    private PendingCountsService() {}

    public static PendingCountsService getInstance() { return INSTANCE; }

    public void addOnChangeListener(Runnable listener) { onChangeListeners.add(listener); }
    public void removeOnChangeListener(Runnable listener) { onChangeListeners.remove(listener); }

    public void notifyChanged() {
        new ArrayList<>(onChangeListeners).forEach(Runnable::run);
    }
}
