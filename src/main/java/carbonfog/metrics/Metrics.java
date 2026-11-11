package carbonfog.metrics;

import carbonfog.model.Task;
import carbonfog.model.TaskType;
import carbonfog.model.Tier;
import carbonfog.nodes.ComputeNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Collects per-task outcomes of one simulation run. */
public final class Metrics {

    public static final class Bucket {
        public long generated, completed, met, dropped;
        public double latencySum, energyJ, carbonG, reward;
        public final long[] tier = new long[Tier.values().length];
    }

    private final int hours;
    public final Bucket total = new Bucket();
    public final Bucket[] hourly;
    public final Map<TaskType, Bucket> byType = new EnumMap<>(TaskType.class);
    private double[] latencies = new double[1 << 16];
    private int nLat;

    public long resubmissions, migrations, detections;
    /** Outcomes of tasks that were on a node when it crashed (resubmitted or dropped). */
    public long affectedCompleted, affectedMet, affectedDropped;
    public double affectedLatencySum;
    /** Health alarms raised: [fog index, time ms]. */
    public final List<double[]> alarms = new ArrayList<>();
    /** Failure detections by heartbeat time-out: [fog index, time ms]. */
    public final List<double[]> detectionLog = new ArrayList<>();
    /** Down-sampled health trace rows: time, fog index, risk, temp excess, errors/min, draining. */
    public final List<double[]> healthTrace = new ArrayList<>();
    /** JSON lines of sampled sensor messages. */
    public final List<String> sensorSample = new ArrayList<>();

    /**
     * Task flows: flows[hour][source zone][target], target 0 = device, 1..4 = fog node index, 5 = cloud.
     * Counted at completion, by the hour the task was created.
     */
    public final long[][][] flows;

    public Metrics(int hours) {
        this.hours = Math.max(1, hours);
        this.flows = new long[this.hours][4][6];
        this.hourly = new Bucket[this.hours];
        for (int i = 0; i < this.hours; i++) hourly[i] = new Bucket();
        for (TaskType t : TaskType.values()) byType.put(t, new Bucket());
    }

    private Bucket hour(double tMs) {
        int h = (int) (tMs / 3_600_000.0);
        return hourly[Math.max(0, Math.min(hours - 1, h))];
    }

    public void onGenerated(Task t) {
        total.generated++;
        hour(t.createdAt).generated++;
        byType.get(t.type).generated++;
    }

    public void onCompleted(Task t, ComputeNode node, double latency, boolean met, double reward) {
        for (Bucket b : new Bucket[]{total, hour(t.createdAt), byType.get(t.type)}) {
            b.completed++;
            if (met) b.met++;
            b.latencySum += latency;
            b.energyJ += t.energyJ;
            b.carbonG += t.carbonG;
            b.reward += reward;
            b.tier[node.tier().ordinal()]++;
        }
        int h = Math.max(0, Math.min(hours - 1, (int) (t.createdAt / 3_600_000.0)));
        int target = switch (node.tier()) {
            case DEVICE -> 0;
            case FOG -> 1 + node.zone();
            case CLOUD -> 5;
        };
        if (t.zone >= 0 && t.zone < 4) flows[h][t.zone][target]++;
        if (nLat == latencies.length) latencies = Arrays.copyOf(latencies, nLat * 2);
        latencies[nLat++] = latency;
        if (t.resubmitted) {
            affectedCompleted++;
            if (met) affectedMet++;
            affectedLatencySum += latency;
        }
    }

    public void onDropped(Task t, double reward) {
        affectedDropped++;
        for (Bucket b : new Bucket[]{total, hour(t.createdAt), byType.get(t.type)}) {
            b.dropped++;
            b.energyJ += t.energyJ;
            b.carbonG += t.carbonG;
            b.reward += reward;
        }
    }

    public double percentileLatency(double p) {
        if (nLat == 0) return 0;
        double[] copy = Arrays.copyOf(latencies, nLat);
        Arrays.sort(copy);
        return copy[(int) Math.min(nLat - 1, Math.floor(p * nLat))];
    }
}
