package carbonfog.policy;

import carbonfog.energy.CarbonModel;
import carbonfog.model.Task;
import carbonfog.model.Tier;
import carbonfog.network.Network;
import carbonfog.nodes.ComputeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Everything a scheduling policy may look at when placing one task: the
 * candidate nodes, which of them are usable right now, and what-if estimates
 * of latency / energy / carbon for each candidate.
 */
public final class DecisionContext {

    private final Task task;
    private final ComputeNode origin;
    private final double now;
    private final List<ComputeNode> fogs;
    private final ComputeNode cloud;
    private final Network network;
    private final CarbonModel carbon;
    private final Predicate<ComputeNode> fogUsable;
    private final ToDoubleFunction<ComputeNode> queueWaitMs;
    private final Map<ComputeNode, Estimate> cache = new HashMap<>();

    public DecisionContext(Task task, ComputeNode origin, double now, List<ComputeNode> fogs, ComputeNode cloud,
                           Network network, CarbonModel carbon, Predicate<ComputeNode> fogUsable,
                           ToDoubleFunction<ComputeNode> queueWaitMs) {
        this.task = task;
        this.origin = origin;
        this.now = now;
        this.fogs = fogs;
        this.cloud = cloud;
        this.network = network;
        this.carbon = carbon;
        this.fogUsable = fogUsable;
        this.queueWaitMs = queueWaitMs;
    }

    public Task task() { return task; }
    public double now() { return now; }
    public ComputeNode device() { return task.device; }
    public ComputeNode cloud() { return cloud; }
    public List<ComputeNode> fogs() { return fogs; }
    public ComputeNode homeFog() { return fogs.get(task.zone); }

    /** True when the decision is made on the device (normal case); false for a migration from a fog node. */
    public boolean fromDevice() { return origin.tier() == Tier.DEVICE; }

    public boolean usable(ComputeNode n) {
        if (n == null) return false;
        if (n.tier() == Tier.DEVICE) return fromDevice() && n == task.device;
        if (n.tier() == Tier.CLOUD) return true;
        return n != origin && fogUsable.test(n);
    }

    public List<ComputeNode> candidates() {
        List<ComputeNode> c = new ArrayList<>();
        if (fromDevice()) c.add(task.device);
        for (ComputeNode f : fogs) if (usable(f)) c.add(f);
        c.add(cloud);
        return c;
    }

    /** What-if estimate for running the task on n (cached per decision). */
    public Estimate estimate(ComputeNode n) {
        return cache.computeIfAbsent(n, this::computeEstimate);
    }

    private Estimate computeEstimate(ComputeNode n) {
        Network.Transfer tr = network.upload(task, origin, n, now, false);
        double wait = queueWaitMs.applyAsDouble(n);
        double exec = n.execTimeMs(task);
        double back = network.returnDelay(task, n, false);
        double latency = (now - task.createdAt) + (tr.arrivalTime() - now) + wait + exec + back;
        double dynJ = n.dynamicWPerCore() * exec / 1000.0;
        double carbonG = CarbonModel.grams(dynJ, marginalIntensity(n)) + tr.carbonG();
        return new Estimate(latency, dynJ + tr.energyJ(), carbonG);
    }

    /** gCO2/kWh of the extra power this task would add on node n right now. */
    public double marginalIntensity(ComputeNode n) {
        return carbon.marginalIntensity(n, now, n.currentDrawW(), n.dynamicWPerCore());
    }

    /** Usable fog other than the home fog with the lowest estimated latency (null if none). */
    public ComputeNode fastestOtherFog() {
        ComputeNode best = null;
        for (ComputeNode f : fogs) {
            if (f == homeFog() || !usable(f)) continue;
            if (best == null || estimate(f).latencyMs() < estimate(best).latencyMs()) best = f;
        }
        return best;
    }

    /** Usable fog other than the home fog with the cleanest marginal electricity (null if none). */
    public ComputeNode greenestOtherFog() {
        ComputeNode best = null;
        double bestI = Double.MAX_VALUE;
        for (ComputeNode f : fogs) {
            if (f == homeFog() || !usable(f)) continue;
            double i = marginalIntensity(f);
            if (i < bestI - 1e-9 || (Math.abs(i - bestI) < 1e-9 && estimate(f).latencyMs() < estimate(best).latencyMs())) {
                best = f;
                bestI = i;
            }
        }
        return best;
    }
}
