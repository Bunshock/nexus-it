package com.bunshock.note_app_for_it_frontend.services;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingCountsServiceTest {

    private final PendingCountsService service = PendingCountsService.getInstance();

    @Test
    void notifyChangedInvokesRegisteredListener() {
        AtomicInteger calls = new AtomicInteger(0);
        Runnable listener = calls::incrementAndGet;

        service.addOnChangeListener(listener);
        try {
            service.notifyChanged();
            assertEquals(1, calls.get());

            service.notifyChanged();
            assertEquals(2, calls.get(), "Each notifyChanged() call must invoke every registered listener again");
        } finally {
            service.removeOnChangeListener(listener);
        }
    }

    @Test
    void removedListenerIsNoLongerInvoked() {
        AtomicInteger calls = new AtomicInteger(0);
        Runnable listener = calls::incrementAndGet;

        service.addOnChangeListener(listener);
        service.removeOnChangeListener(listener);
        service.notifyChanged();

        assertEquals(0, calls.get(), "A removed listener must not be invoked by a later notifyChanged()");
    }

    @Test
    void notifyChangedInvokesEveryRegisteredListenerIndependently() {
        AtomicInteger callsA = new AtomicInteger(0);
        AtomicInteger callsB = new AtomicInteger(0);
        Runnable listenerA = callsA::incrementAndGet;
        Runnable listenerB = callsB::incrementAndGet;

        service.addOnChangeListener(listenerA);
        service.addOnChangeListener(listenerB);
        try {
            service.notifyChanged();
            assertEquals(1, callsA.get());
            assertEquals(1, callsB.get());
        } finally {
            service.removeOnChangeListener(listenerA);
            service.removeOnChangeListener(listenerB);
        }
    }
}
