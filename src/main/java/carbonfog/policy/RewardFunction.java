package carbonfog.policy;

import carbonfog.config.SimConfig;
import carbonfog.model.Task;

/**
 * Multi-objective reward for one finished task:
 *
 *   r = -( wL * latency/deadline + wE * energy/E_ref + wC * carbon/C_ref + missPenalty * [missed] )
 *
 * Energy and carbon are normalised by what the task would cost on a reference
 * fog core (4000 MIPS, 22.5 W) on a typical coal-heavy grid (700 g/kWh), so the
 * three objectives have comparable scale across task types.
 */
public final class RewardFunction {

    private static final double REF_MIPS = 4000, REF_WATTS = 22.5, REF_INTENSITY = 700;

    private final SimConfig cfg;

    public RewardFunction(SimConfig cfg) {
        this.cfg = cfg;
    }

    public double completed(Task t, double latencyMs, boolean metDeadline) {
        double eRef = t.mi / REF_MIPS * REF_WATTS;
        double cRef = eRef / 3.6e6 * REF_INTENSITY;
        double lat = Math.min(latencyMs / t.deadlineMs, 3.0);
        double e = Math.min(t.energyJ / eRef, 5.0);
        double c = Math.min(t.carbonG / cRef, 5.0);
        return -(cfg.wLatency * lat + cfg.wEnergy * e + cfg.wCarbon * c + (metDeadline ? 0 : cfg.missPenalty));
    }

    public double lost() {
        return -cfg.lostPenalty;
    }
}
