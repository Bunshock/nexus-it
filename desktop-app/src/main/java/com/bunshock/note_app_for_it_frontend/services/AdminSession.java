package com.bunshock.note_app_for_it_frontend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

public class AdminSession {

    private static final int TIMEOUT_MINUTES = 15;
    private static final AdminSession INSTANCE = new AdminSession();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "admin-session-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean active = false;
    private volatile boolean neverExpires = false;
    private volatile LocalDateTime lastActivity;
    private ScheduledFuture<?> timeoutCheck;

    private final List<Runnable> onActivateListeners   = new ArrayList<>();
    private final List<Runnable> onDeactivateListeners = new ArrayList<>();
    private final List<Runnable> onExpireListeners     = new ArrayList<>();

    private AdminSession() {}

    public static AdminSession getInstance() { return INSTANCE; }

    public synchronized void activate() {
        active = true;
        neverExpires = false;
        lastActivity = LocalDateTime.now();
        scheduleCheck();
        Platform.runLater(() -> new ArrayList<>(onActivateListeners).forEach(Runnable::run));
    }

    /**
     * Activates without ever expiring on inactivity — used for an ADMIN-role login, since the
     * technician's own credentials already proved who they are, and the old self-service
     * "Activar modo administrador" password toggle (the only way anyone used to get back in
     * after the 15-minute timeout) no longer exists for anyone to click.
     */
    public synchronized void activatePermanently() {
        active = true;
        neverExpires = true;
        lastActivity = null;
        if (timeoutCheck != null) timeoutCheck.cancel(false);
        Platform.runLater(() -> new ArrayList<>(onActivateListeners).forEach(Runnable::run));
    }

    public synchronized void deactivate() {
        if (!active) return;
        active = false;
        neverExpires = false;
        lastActivity = null;
        if (timeoutCheck != null) timeoutCheck.cancel(false);
        List<Runnable> listeners = new ArrayList<>(onDeactivateListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    public synchronized boolean isActive() {
        if (!active) return false;
        if (neverExpires) return true;
        if (lastActivity == null ||
            LocalDateTime.now().isAfter(lastActivity.plusMinutes(TIMEOUT_MINUTES))) {
            expire();
            return false;
        }
        return true;
    }

    public synchronized void refreshActivity() {
        if (active && !neverExpires) lastActivity = LocalDateTime.now();
    }

    public long getRemainingSeconds() {
        if (!active || neverExpires || lastActivity == null) return 0;
        long elapsed = java.time.temporal.ChronoUnit.SECONDS.between(lastActivity, LocalDateTime.now());
        return Math.max(0, TIMEOUT_MINUTES * 60L - elapsed);
    }

    public void addOnActivateListener(Runnable listener)     { onActivateListeners.add(listener); }
    public void removeOnActivateListener(Runnable listener)  { onActivateListeners.remove(listener); }

    public void addOnDeactivateListener(Runnable listener)   { onDeactivateListeners.add(listener); }
    public void removeOnDeactivateListener(Runnable listener){ onDeactivateListeners.remove(listener); }

    public void addOnExpireListener(Runnable listener)       { onExpireListeners.add(listener); }
    public void removeOnExpireListener(Runnable listener)    { onExpireListeners.remove(listener); }

    private void scheduleCheck() {
        if (timeoutCheck != null && !timeoutCheck.isDone()) timeoutCheck.cancel(false);
        timeoutCheck = scheduler.scheduleAtFixedRate(() -> {
            if (active && lastActivity != null &&
                LocalDateTime.now().isAfter(lastActivity.plusMinutes(TIMEOUT_MINUTES))) {
                Platform.runLater(this::expire);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private synchronized void expire() {
        if (!active) return;
        active = false;
        lastActivity = null;
        List<Runnable> deact = new ArrayList<>(onDeactivateListeners);
        List<Runnable> exp   = new ArrayList<>(onExpireListeners);
        Platform.runLater(() -> {
            deact.forEach(Runnable::run);
            exp.forEach(Runnable::run);
        });
    }
}
