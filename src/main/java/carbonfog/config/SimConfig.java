package carbonfog.config;

/**
 * All tunable parameters of one simulation run. Defaults describe the
 * smart-campus scenario used in the report; experiments copy and tweak it.
 */
public final class SimConfig {

    /** Master random seed: drives sensor data, arrivals, weather and failures. */
    public long seed = 1000;

    /** Simulated wall-clock length in hours (starts at 00:00 of day 0). */
    public double hours = 24;

    /** Multiplier on every sensor's task arrival rate (1.0 = nominal campus load). */
    public double loadScale = 1.0;

    // ----- multi-objective reward weights (latency, energy, carbon) -----
    // Latency below the deadline matters only mildly; missing the deadline is penalised hard.
    public double wLatency = 0.3;
    public double wEnergy = 0.3;
    public double wCarbon = 1.0;
    /** Extra penalty when a task misses its deadline. */
    public double missPenalty = 2.0;
    /** Penalty when a task is lost because its node crashed. */
    public double lostPenalty = 4.0;

    // ----- fog-node failures -----
    public boolean failures = true;
    /** Mean number of crashes per fog node per simulated day (accelerated for experiments). */
    public double failuresPerNodePerDay = 1.0;
    /** Fraction of crashes that show degradation symptoms (heat, ECC errors) beforehand. */
    public double predictableFraction = 0.75;
    /** Harmless telemetry anomalies per node per day (these can trigger false alarms). */
    public double anomalySpikesPerNodePerDay = 2.0;
    public FaultMode faultMode = FaultMode.PROACTIVE;

    public double heartbeatMs = 2000;
    /** A node is declared down after this many consecutive missing heartbeats. */
    public int missedHeartbeats = 3;
    /** Maximum dispatch attempts per task (first try + resubmissions). */
    public int maxAttempts = 3;

    /** Record a down-sampled health/risk trace of every fog node (for plots). */
    public boolean traceHealth = false;
    /** Record JSON sensor messages created in this window [from,to) ms (for the report). */
    public double sampleSensorFromMs = -1, sampleSensorToMs = -1;

    public SimConfig copy() {
        SimConfig c = new SimConfig();
        c.seed = seed; c.hours = hours; c.loadScale = loadScale;
        c.wLatency = wLatency; c.wEnergy = wEnergy; c.wCarbon = wCarbon;
        c.missPenalty = missPenalty; c.lostPenalty = lostPenalty;
        c.failures = failures; c.failuresPerNodePerDay = failuresPerNodePerDay;
        c.predictableFraction = predictableFraction; c.anomalySpikesPerNodePerDay = anomalySpikesPerNodePerDay;
        c.faultMode = faultMode; c.heartbeatMs = heartbeatMs; c.missedHeartbeats = missedHeartbeats;
        c.maxAttempts = maxAttempts; c.traceHealth = traceHealth;
        c.sampleSensorFromMs = sampleSensorFromMs; c.sampleSensorToMs = sampleSensorToMs;
        return c;
    }

    public SimConfig withSeed(long s) { SimConfig c = copy(); c.seed = s; return c; }

    public SimConfig withWeights(double l, double e, double co2) {
        SimConfig c = copy(); c.wLatency = l; c.wEnergy = e; c.wCarbon = co2; return c;
    }

    public SimConfig withFaultMode(FaultMode m) { SimConfig c = copy(); c.faultMode = m; return c; }
}
