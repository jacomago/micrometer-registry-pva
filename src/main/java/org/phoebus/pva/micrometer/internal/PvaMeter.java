package org.phoebus.pva.micrometer.internal;

/**
 * Internal interface for PVA-side meter wrappers.
 *
 * <p>Each implementation manages a {@link org.epics.pva.server.ServerPV} and
 * knows how to read values from its corresponding Micrometer meter.
 */
public interface PvaMeter {

    /**
     * Poll the current meter value(s) and push the update to PVA subscribers.
     *
     * @param alwaysPublish when {@code true}, publish even if the value has not changed
     */
    void tick(boolean alwaysPublish);

    /**
     * Close the underlying {@link org.epics.pva.server.ServerPV}, signalling
     * PVA clients to disconnect.
     */
    void close();
}
