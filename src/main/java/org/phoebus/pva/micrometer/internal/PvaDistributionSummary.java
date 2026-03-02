package org.phoebus.pva.micrometer.internal;

import io.micrometer.core.instrument.AbstractDistributionSummary;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVALong;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.data.nt.PVAAlarm;
import org.epics.pva.data.nt.PVAAlarm.AlarmSeverity;
import org.epics.pva.data.nt.PVAAlarm.AlarmStatus;
import org.epics.pva.data.nt.PVATimeStamp;
import org.epics.pva.server.ServerPV;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wraps a Micrometer {@link io.micrometer.core.instrument.DistributionSummary} as a custom
 * PVA structure with type name {@code micrometer:Summary:1.0}.
 *
 * <p>The PV structure contains four fields published on every poll tick:
 * <ul>
 *   <li>{@code count} — cumulative number of observations (long)</li>
 *   <li>{@code total} — cumulative sum of all observed values (double, scaled)</li>
 *   <li>{@code max} — maximum single observed value (double, scaled)</li>
 *   <li>{@code alarm} / {@code timeStamp} — standard EPICS metadata</li>
 * </ul>
 *
 * <p>The {@code total} and {@code max} values reflect the
 * {@code scale} factor supplied at construction time, consistent with the
 * {@link io.micrometer.core.instrument.DistributionSummary} contract.
 *
 * <p>This class is package-internal; use
 * {@link org.phoebus.pva.micrometer.PvaMeterRegistry} to register distribution summaries.
 */
public final class PvaDistributionSummary extends AbstractDistributionSummary {

    private final LongAdder count = new LongAdder();
    private final DoubleAdder total = new DoubleAdder();
    // Max stored as raw long bits so it can be CAS-updated without locking.
    private final AtomicLong maxBits = new AtomicLong(Double.doubleToRawLongBits(0.0));

    /** The mutable structure updated in-place on every poll tick. */
    private final PVAStructure data;

    /** Cached field references for efficient in-place updates. */
    private final PVALong countField;
    private final PVADouble totalField;
    private final PVADouble maxField;
    private final PVAAlarm alarmField;

    /**
     * Creates a distribution summary wrapper.
     *
     * @param id                          meter identity (name + tags)
     * @param clock                       Micrometer clock
     * @param distributionStatisticConfig histogram/percentile config (typically NONE)
     * @param scale                       scale factor applied by the base class before
     *                                    {@link #recordNonNegative(double)} is called
     */
    public PvaDistributionSummary(Meter.Id id, Clock clock,
            DistributionStatisticConfig distributionStatisticConfig, double scale) {
        super(id, clock, distributionStatisticConfig, scale, false);
        this.data = buildInitialData();
        this.countField = data.get("count");
        this.totalField = data.get("total");
        this.maxField = data.get("max");
        this.alarmField = data.get("alarm");
    }

    private static PVAStructure buildInitialData() {
        return new PVAStructure("", "micrometer:Summary:1.0",
                new PVALong("count", false, 0L),
                new PVADouble("total", 0.0),
                new PVADouble("max", 0.0),
                new PVAAlarm(),
                new PVATimeStamp());
    }

    /**
     * Records a scaled, non-negative amount. Invoked by the base class after applying
     * the scale factor and validating the sign.
     */
    @Override
    protected void recordNonNegative(double amount) {
        count.increment();
        total.add(amount);
        // CAS loop to update running maximum without locking.
        long amountBits = Double.doubleToRawLongBits(amount);
        long prevBits;
        do {
            prevBits = maxBits.get();
        } while (amount > Double.longBitsToDouble(prevBits)
                && !maxBits.compareAndSet(prevBits, amountBits));
    }

    @Override
    public long count() {
        return count.sum();
    }

    @Override
    public double totalAmount() {
        return total.sum();
    }

    @Override
    public double max() {
        return Double.longBitsToDouble(maxBits.get());
    }

    /**
     * Returns the initial PVA structure used when registering this PV with the server.
     * The same instance is updated in-place on every poll tick.
     */
    public PVAStructure getInitialData() {
        return data;
    }

    /**
     * Reads current distribution statistics, updates the PVA structure, and pushes to clients.
     *
     * @param serverPV the PVA channel to update
     * @throws Exception if {@code serverPV.update()} fails
     */
    public void updatePv(ServerPV serverPV) throws Exception {
        try {
            countField.set(count());
            totalField.set(totalAmount());
            maxField.set(max());
            alarmField.set(AlarmSeverity.NO_ALARM, AlarmStatus.NO_STATUS, "");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage()
                    : "Error reading distribution summary value";
            alarmField.set(AlarmSeverity.INVALID, AlarmStatus.DRIVER, msg);
        }
        PVATimeStamp.set(data, Instant.now());
        serverPV.update(data);
    }
}
