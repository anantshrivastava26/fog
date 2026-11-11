package carbonfog.failure;

import carbonfog.nodes.Telemetry;

import java.util.HashMap;
import java.util.Map;

/**
 * Online failure predictor running in the orchestrator. For every fog node it
 * smooths two health signals from the heartbeats - temperature above what the
 * current load explains, and the rate of corrected memory (ECC) errors - and
 * turns them into a crash-risk score with a logistic model. An alarm needs
 * five consecutive high-risk heartbeats (filters short glitches); the alarm
 * clears after the node has looked healthy for 15 heartbeats.
 */
public final class HealthMonitor {

    public enum Signal { NONE, ALARM, CLEAR }

    public static final double ALARM_RISK = 0.8;
    public static final double CLEAR_RISK = 0.3;
    private static final int ALARM_STREAK = 5;
    private static final int CLEAR_STREAK = 15;

    private static final class NodeHealth {
        double excess;       // EWMA of (temp - expected temp)
        double errPerMin;    // EWMA of ECC error rate
        double risk;
        int high;
        int low;
        boolean alarmed;
    }

    private final double heartbeatMs;
    private final Map<Integer, NodeHealth> nodes = new HashMap<>();

    public HealthMonitor(double heartbeatMs) {
        this.heartbeatMs = heartbeatMs;
    }

    public Signal update(Telemetry tm) {
        NodeHealth h = nodes.computeIfAbsent(tm.nodeId(), k -> new NodeHealth());
        h.excess = 0.5 * h.excess + 0.5 * (tm.tempC() - tm.expectedTempC());
        h.errPerMin = 0.85 * h.errPerMin + 0.15 * (tm.eccErrors() * 60_000.0 / heartbeatMs);
        double z = 0.30 * (h.excess - 9) + 0.45 * (h.errPerMin - 3);
        h.risk = 1.0 / (1.0 + Math.exp(-z));
        h.high = h.risk >= ALARM_RISK ? h.high + 1 : 0;
        h.low = h.risk < CLEAR_RISK ? h.low + 1 : 0;
        if (!h.alarmed && h.high >= ALARM_STREAK) {
            h.alarmed = true;
            return Signal.ALARM;
        }
        if (h.alarmed && h.low >= CLEAR_STREAK) {
            h.alarmed = false;
            return Signal.CLEAR;
        }
        return Signal.NONE;
    }

    public double risk(int nodeId) {
        NodeHealth h = nodes.get(nodeId);
        return h == null ? 0 : h.risk;
    }

    public double smoothedExcess(int nodeId) {
        NodeHealth h = nodes.get(nodeId);
        return h == null ? 0 : h.excess;
    }

    public double smoothedErrors(int nodeId) {
        NodeHealth h = nodes.get(nodeId);
        return h == null ? 0 : h.errPerMin;
    }
}
