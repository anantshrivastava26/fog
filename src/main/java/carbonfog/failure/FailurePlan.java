package carbonfog.failure;

import carbonfog.config.SimConfig;
import carbonfog.util.Rand;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pre-computed fault schedule for one run (same seed => same faults for every
 * policy, so comparisons are fair).
 */
public final class FailurePlan {

    private static final double MIN = 60_000.0;

    /** A crash. degradeStart < 0 means a sudden crash with no warning signs (e.g. power cut). */
    public record Crash(int fogIndex, double degradeStart, double failAt, double recoverAt) {
        public boolean predictable() { return degradeStart >= 0; }
    }

    /** A harmless telemetry anomaly (e.g. HVAC glitch, transient bit flips). */
    public record Spike(int fogIndex, double start, double durationMs, double tempExcess, double errorRatePerMin) { }

    public final List<Crash> crashes = new ArrayList<>();
    public final List<Spike> spikes = new ArrayList<>();

    public static FailurePlan generate(SimConfig cfg, int fogCount, double horizonMs) {
        FailurePlan plan = new FailurePlan();
        if (!cfg.failures) return plan;
        Random r = Rand.rng(cfg.seed * 1_000_003L + 11);
        double day = 24 * 60 * MIN;
        for (int f = 0; f < fogCount; f++) {
            double t = 0;
            double lastRecover = 0;
            while (cfg.failuresPerNodePerDay > 0) {
                t += Rand.exponential(r, day / cfg.failuresPerNodePerDay);
                if (t > horizonMs - 20 * MIN) break;
                boolean predictable = r.nextDouble() < cfg.predictableFraction;
                double lead = Rand.uniform(r, 3, 12) * MIN;
                double degradeStart = predictable ? t - lead : -1;
                if (t - lead < lastRecover + 10 * MIN) { continue; }   // keep faults apart
                double recover = t + Rand.uniform(r, 10, 30) * MIN;
                plan.crashes.add(new Crash(f, degradeStart, t, recover));
                lastRecover = recover;
                t = recover;
            }
            double s = 0;
            while (cfg.anomalySpikesPerNodePerDay > 0) {
                s += Rand.exponential(r, day / cfg.anomalySpikesPerNodePerDay);
                if (s > horizonMs) break;
                boolean withErrors = r.nextDouble() < 0.4;
                plan.spikes.add(new Spike(f, s, Rand.uniform(r, 60, 150) * 1000,
                        Rand.uniform(r, 10, 16), withErrors ? Rand.uniform(r, 1.5, 4.5) : 0));
            }
        }
        return plan;
    }
}
