package carbonfog.energy;

import carbonfog.util.Rand;
import carbonfog.nodes.ComputeNode;

import java.util.Random;

/**
 * Simulated, time-varying carbon intensity of electricity (gCO2e per kWh) and
 * on-site solar generation. Everything is a deterministic function of time and
 * the seed, so every policy in an experiment sees exactly the same "weather".
 */
public final class CarbonModel {

    /** Life-cycle emissions of rooftop PV electricity (IPCC AR5 median ~41 g/kWh). */
    public static final double SOLAR_LCA = 41.0;

    private static final double HOUR_MS = 3_600_000.0;

    private final double[] clearness;   // per-day sky clearness 0.35..1.0
    private final double[] phase;       // per-day phase for smooth fluctuations

    public CarbonModel(long seed, int days) {
        Random r = Rand.rng(seed * 7919L + 17);
        clearness = new double[days + 2];
        phase = new double[days + 2];
        for (int d = 0; d < clearness.length; d++) {
            clearness[d] = 0.35 + 0.65 * r.nextDouble();
            phase[d] = r.nextDouble() * 2 * Math.PI;
        }
    }

    public static double hourOfDay(double tMs) { return (tMs / HOUR_MS) % 24.0; }

    private int day(double tMs) { return Math.min(clearness.length - 1, (int) (tMs / (24 * HOUR_MS))); }

    /** Bell-shaped daylight curve between 06:00 and 18:00 (0 at night, 1 at noon). */
    private static double daylight(double h) {
        return (h < 6 || h >= 18) ? 0.0 : Math.sin(Math.PI * (h - 6) / 12.0);
    }

    /** Fraction (0..1) of rated PV output available at time t, including passing clouds. */
    public double solarIrradiance(double tMs) {
        double h = hourOfDay(tMs);
        double base = daylight(h);
        if (base <= 0) return 0;
        int d = day(tMs);
        double passingClouds = 0.80 + 0.20 * Math.sin(2 * Math.PI * tMs / (23 * 60_000.0) + phase[d]);
        return Math.max(0, Math.min(1, base * clearness[d] * passingClouds));
    }

    /** Average grid intensity of a region at time t. */
    public double gridIntensity(Region region, double tMs) {
        double h = hourOfDay(tMs);
        double sun = daylight(h);
        double wobble = 15 * Math.sin(2 * Math.PI * tMs / (3.1 * HOUR_MS) + phase[day(tMs)]);
        return switch (region) {
            // coal-heavy grid: evening peak at 20:00, some utility solar dip at mid-day
            case CAMPUS_GRID -> 700 + 55 * Math.cos(2 * Math.PI * (h - 20) / 24) - 90 * sun + wobble;
            // renewable-rich cloud region
            case CLOUD_REGION -> 190 + 45 * Math.cos(2 * Math.PI * (h - 19) / 24) - 70 * sun + 0.6 * wobble;
        };
    }

    /** Intensity of all the power a node currently draws, after on-site solar covers what it can. */
    public double effectiveIntensity(ComputeNode n, double tMs, double drawW) {
        double grid = gridIntensity(n.spec().region(), tMs);
        if (n.spec().solarKWp() <= 0 || drawW <= 0) return grid;
        double solarW = n.spec().solarKWp() * 1000 * solarIrradiance(tMs);
        double green = Math.min(1.0, solarW / drawW);
        return green * SOLAR_LCA + (1 - green) * grid;
    }

    /**
     * Intensity of an *extra* load of extraW on top of baseDrawW: only the
     * solar surplus left after the existing load is "green" for the new task.
     */
    public double marginalIntensity(ComputeNode n, double tMs, double baseDrawW, double extraW) {
        double grid = gridIntensity(n.spec().region(), tMs);
        if (n.spec().solarKWp() <= 0 || extraW <= 0) return grid;
        double solarW = n.spec().solarKWp() * 1000 * solarIrradiance(tMs);
        double surplus = Math.max(0, solarW - baseDrawW);
        double green = Math.min(1.0, surplus / extraW);
        return green * SOLAR_LCA + (1 - green) * grid;
    }

    /** Joules at a given intensity -> grams CO2e. */
    public static double grams(double joules, double gPerKWh) {
        return joules / 3.6e6 * gPerKWh;
    }
}
