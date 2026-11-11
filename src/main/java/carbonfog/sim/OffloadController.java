package carbonfog.sim;

import carbonfog.config.FaultMode;
import carbonfog.config.SimConfig;
import carbonfog.energy.CarbonModel;
import carbonfog.failure.HealthMonitor;
import carbonfog.metrics.Metrics;
import carbonfog.model.Task;
import carbonfog.network.Network;
import carbonfog.nodes.ComputeNode;
import carbonfog.nodes.MigrationBatch;
import carbonfog.nodes.Telemetry;
import carbonfog.policy.DecisionContext;
import carbonfog.policy.OffloadPolicy;
import carbonfog.policy.RewardFunction;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fog orchestrator (SDN-controller style, hosted on the campus fog layer).
 * It receives every task, asks the policy where to run it, books the network
 * transfer, tracks outstanding work per node, turns completions into rewards,
 * watches heartbeats to detect crashes, and - in PROACTIVE mode - uses the
 * {@link HealthMonitor} to drain at-risk nodes and migrate their queues.
 */
public final class OffloadController extends SimEntity {

    private static final double MONITOR_MS = 1000;

    /** Controller-side view of a node (it knows what it dispatched). */
    private static final class NodeView {
        final Set<Task> outstanding = new LinkedHashSet<>();
        double outstandingMI;
        double lastHeartbeat;
        boolean declaredDown;
        boolean draining;
        int traceCounter;
    }

    private final SimConfig cfg;
    private final List<ComputeNode> fogs;
    private final ComputeNode cloud;
    private final Network network;
    private final CarbonModel carbon;
    private final OffloadPolicy policy;
    private final Metrics metrics;
    private final RewardFunction reward;
    private final HealthMonitor health;
    private final Map<Integer, NodeView> views = new HashMap<>();
    private final Map<Integer, Integer> fogIndexById = new HashMap<>();

    public OffloadController(SimConfig cfg, List<ComputeNode> fogs, ComputeNode cloud, Network network,
                             CarbonModel carbon, OffloadPolicy policy, Metrics metrics) {
        super("fog-orchestrator");
        this.cfg = cfg;
        this.fogs = fogs;
        this.cloud = cloud;
        this.network = network;
        this.carbon = carbon;
        this.policy = policy;
        this.metrics = metrics;
        this.reward = new RewardFunction(cfg);
        this.health = new HealthMonitor(cfg.heartbeatMs);
        for (int i = 0; i < fogs.size(); i++) fogIndexById.put(fogs.get(i).getId(), i);
    }

    private NodeView view(ComputeNode n) {
        return views.computeIfAbsent(n.getId(), k -> new NodeView());
    }

    @Override
    public void startEntity() {
        for (ComputeNode f : fogs) view(f).lastHeartbeat = 0;
        send(getId(), MONITOR_MS, OffloadEvents.MONITOR_TICK);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (!(ev.getTag() instanceof OffloadEvents tag)) return;
        switch (tag) {
            case TASK_SUBMIT -> {
                Task t = (Task) ev.getData();
                metrics.onGenerated(t);
                sampleSensorMessage(t);
                dispatch(t, t.device);
            }
            case TASK_EXECUTED -> onExecuted((Task) ev.getData());
            case HEARTBEAT -> onHeartbeat((Telemetry) ev.getData());
            case MONITOR_TICK -> {
                checkTimeouts();
                send(getId(), MONITOR_MS, OffloadEvents.MONITOR_TICK);
            }
            case MIGRATED_TASKS -> onMigrated((MigrationBatch) ev.getData());
            default -> { }
        }
    }

    // ------------------------------------------------------------ dispatching

    private DecisionContext context(Task t, ComputeNode origin) {
        return new DecisionContext(t, origin, CloudSim.clock(), fogs, cloud, network, carbon,
                this::fogUsable, this::queueWaitMs);
    }

    private boolean fogUsable(ComputeNode f) {
        NodeView v = view(f);
        return !v.declaredDown && !v.draining;
    }

    /** Fluid estimate of queueing delay from the work the controller has sent to n. */
    private double queueWaitMs(ComputeNode n) {
        NodeView v = view(n);
        if (v.outstanding.size() < n.spec().cores()) return 0;
        return v.outstandingMI / (n.spec().cores() * n.spec().mipsPerCore()) * 1000.0;
    }

    private void dispatch(Task t, ComputeNode origin) {
        double now = CloudSim.clock();
        DecisionContext ctx = context(t, origin);
        ComputeNode target = policy.decide(t, ctx);
        if (!ctx.usable(target)) target = cloud;      // safety net for buggy policies
        Network.Transfer tr = network.upload(t, origin, target, now, true);
        t.energyJ += tr.energyJ();
        t.carbonG += tr.carbonG();
        t.target = target;
        t.attempts++;
        t.state = Task.State.IN_TRANSIT;
        NodeView v = view(target);
        v.outstanding.add(t);
        v.outstandingMI += t.mi;
        send(target.getId(), tr.arrivalTime() - now, OffloadEvents.TASK_ARRIVE, t);
    }

    private void onExecuted(Task t) {
        ComputeNode node = t.target;
        NodeView v = view(node);
        if (!v.outstanding.remove(t)) return;          // already written off as lost
        v.outstandingMI -= t.mi;
        double latency = CloudSim.clock() + network.returnDelay(t, node, true) - t.createdAt;
        boolean met = latency <= t.deadlineMs;
        t.state = Task.State.DONE;
        double r = reward.completed(t, latency, met);
        metrics.onCompleted(t, node, latency, met, r);
        policy.onCompleted(t, r, context(t, t.device));
    }

    // ------------------------------------------------------ fault tolerance

    private void onHeartbeat(Telemetry tm) {
        ComputeNode node = (ComputeNode) CloudSim.getEntity(tm.nodeId());
        NodeView v = view(node);
        v.lastHeartbeat = CloudSim.clock();
        if (v.declaredDown) v.declaredDown = false;    // node is back after repair
        HealthMonitor.Signal s = health.update(tm);
        int fogIdx = fogIndexById.get(tm.nodeId());
        if (s == HealthMonitor.Signal.ALARM) {
            metrics.alarms.add(new double[]{fogIdx, CloudSim.clock()});
            if (cfg.faultMode == FaultMode.PROACTIVE) {
                v.draining = true;                      // stop sending new work ...
                send(node.getId(), 1.0, OffloadEvents.MIGRATE_QUEUED);   // ... and pull back the queue
            }
        } else if (s == HealthMonitor.Signal.CLEAR) {
            v.draining = false;
        }
        if (cfg.traceHealth && (v.traceCounter++ % 5 == 0)) {
            metrics.healthTrace.add(new double[]{CloudSim.clock(), fogIdx, health.risk(tm.nodeId()),
                    health.smoothedExcess(tm.nodeId()), health.smoothedErrors(tm.nodeId()), v.draining ? 1 : 0});
        }
    }

    private void checkTimeouts() {
        double now = CloudSim.clock();
        double limit = cfg.heartbeatMs * cfg.missedHeartbeats + 500;
        for (ComputeNode f : fogs) {
            NodeView v = view(f);
            if (!v.declaredDown && now - v.lastHeartbeat > limit) declareDown(f, v);
        }
    }

    private void declareDown(ComputeNode f, NodeView v) {
        v.declaredDown = true;
        metrics.detections++;
        metrics.detectionLog.add(new double[]{fogIndexById.get(f.getId()), CloudSim.clock()});
        List<Task> lost = new ArrayList<>(v.outstanding);
        v.outstanding.clear();
        v.outstandingMI = 0;
        for (Task t : lost) {
            policy.onLost(t, reward.lost());
            if (cfg.faultMode == FaultMode.NO_RECOVERY || t.attempts >= cfg.maxAttempts) {
                t.state = Task.State.DROPPED;
                metrics.onDropped(t, reward.lost());
            } else {
                t.resubmitted = true;
                metrics.resubmissions++;
                dispatch(t, t.device);                 // device still holds the input data
            }
        }
    }

    private void onMigrated(MigrationBatch batch) {
        NodeView v = view(batch.from());
        for (Task t : batch.tasks()) {
            if (!v.outstanding.remove(t)) continue;
            v.outstandingMI -= t.mi;
            t.migrated = true;
            metrics.migrations++;
            dispatch(t, batch.from());                 // data moves fog -> fog/cloud
        }
    }

    // ---------------------------------------------------------------- misc

    @SuppressWarnings("unchecked")
    private void sampleSensorMessage(Task t) {
        if (t.createdAt < cfg.sampleSensorFromMs || t.createdAt >= cfg.sampleSensorToMs) return;
        JSONObject msg = new JSONObject();
        msg.put("topic", "campus/zone" + t.zone + "/" + t.device.getName() + "/" + t.type.shortName());
        msg.put("qos", 1);
        msg.put("task_type", t.type.name());
        msg.put("mi", Math.round(t.mi));
        msg.put("payload", new JSONObject(t.payload));
        metrics.sensorSample.add(msg.toJSONString());
    }

    @Override
    public void shutdownEntity() { }

    public long outstandingTasks() {
        long n = 0;
        for (NodeView v : views.values()) n += v.outstanding.size();
        return n;
    }
}
