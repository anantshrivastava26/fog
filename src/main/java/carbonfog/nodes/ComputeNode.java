package carbonfog.nodes;

import carbonfog.energy.CarbonModel;
import carbonfog.failure.FailurePlan;
import carbonfog.model.NodeSpec;
import carbonfog.model.Task;
import carbonfog.model.Tier;
import carbonfog.sim.OffloadEvents;
import carbonfog.util.Rand;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * An iFogSim {@link FogDevice} (IoT device, fog node or cloud) extended with
 * per-task offloading execution: a multi-core FIFO run queue, energy and
 * carbon accounting through iFogSim's {@link FogLinearPowerModel}, health
 * telemetry heartbeats, crash/recovery and queue hand-back for migration.
 */
public final class ComputeNode extends FogDevice {

    private static final double ENERGY_TICK_MS = 60_000;

    private final NodeSpec spec;
    private final CarbonModel carbon;
    private final Random rnd;
    private final double heartbeatMs;

    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private final LinkedHashSet<Task> running = new LinkedHashSet<>();
    private boolean failed;
    private int epoch;

    // energy accounting (integrated over time, includes idle power and PUE)
    private double lastAccounting;
    private double energyJ;
    private double carbonG;
    private double busyCoreMs;
    private double downtimeMs;
    private long executedCount;
    private long lostOnCrash;
    private long droppedWhileDown;

    // per-hour timeline (for the web dashboard's campus replay)
    private double[] hourBusyCoreMs = new double[1];
    private double[] hourCarbonG = new double[1];
    private double[] hourDownMs = new double[1];
    private long[] hourExecuted = new long[1];

    // hidden health state driven by the failure injector
    private double degradeStart = -1;
    private double degradeFailAt = -1;
    private double spikeUntil = -1;
    private double spikeTemp;
    private double spikeErrRate;

    public static ComputeNode create(NodeSpec spec, CarbonModel carbon, double heartbeatMs, long seed) {
        List<Pe> pes = new ArrayList<>();
        for (int i = 0; i < spec.cores(); i++) pes.add(new Pe(i, new PeProvisionerOverbooking(spec.mipsPerCore())));
        PowerHost host = new PowerHost(FogUtils.generateEntityId(),
                new RamProvisionerSimple(spec.tier() == Tier.CLOUD ? 256_000 : 8_000),
                new BwProvisionerOverbooking(10_000), 1_000_000, pes,
                new StreamOperatorScheduler(pes),
                new FogLinearPowerModel(spec.maxW(), spec.idleW()));
        try {
            return new ComputeNode(spec, host, carbon, heartbeatMs, seed);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create node " + spec.name(), e);
        }
    }

    private ComputeNode(NodeSpec spec, PowerHost host, CarbonModel carbon, double heartbeatMs, long seed)
            throws Exception {
        super(spec.name(),
                new FogDeviceCharacteristics("x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0),
                new AppModuleAllocationPolicy(List.of(host)), new LinkedList<Storage>(), 10,
                10_000, 10_000, 0, 0.0);
        this.spec = spec;
        this.carbon = carbon;
        this.heartbeatMs = heartbeatMs;
        this.rnd = Rand.rng(seed);
        setLevel(switch (spec.tier()) { case CLOUD -> 0; case FOG -> 1; case DEVICE -> 2; });
    }

    // ------------------------------------------------------------------ events

    @Override
    public void startEntity() {
        super.startEntity();
        send(getId(), ENERGY_TICK_MS, OffloadEvents.ENERGY_TICK);
        if (spec.tier() == Tier.FOG) send(getId(), heartbeatMs * rnd.nextDouble(), OffloadEvents.HEARTBEAT_TICK);
    }

    @Override
    protected void processOtherEvent(SimEvent ev) {
        if (!(ev.getTag() instanceof OffloadEvents tag)) {
            super.processOtherEvent(ev);
            return;
        }
        switch (tag) {
            case TASK_ARRIVE -> onArrive((Task) ev.getData());
            case TASK_FINISH -> onFinish((Task) ev.getData());
            case ENERGY_TICK -> {
                account();
                send(getId(), ENERGY_TICK_MS, OffloadEvents.ENERGY_TICK);
            }
            case HEARTBEAT_TICK -> {
                if (!failed) sendHeartbeat();
                send(getId(), heartbeatMs, OffloadEvents.HEARTBEAT_TICK);
            }
            case DEGRADE_START -> {
                degradeStart = CloudSim.clock();
                degradeFailAt = (Double) ev.getData();
            }
            case SPIKE_START -> {
                FailurePlan.Spike s = (FailurePlan.Spike) ev.getData();
                spikeUntil = CloudSim.clock() + s.durationMs();
                spikeTemp = s.tempExcess();
                spikeErrRate = s.errorRatePerMin();
            }
            case NODE_FAIL -> crash();
            case NODE_RECOVER -> recover();
            case MIGRATE_QUEUED -> handBackQueue();
            default -> { }
        }
    }

    private void onArrive(Task t) {
        if (t.target != this) return;            // stale copy of a task that was re-routed
        account();
        if (failed) {                            // data sent to a dead node is silently lost
            t.state = Task.State.LOST;
            droppedWhileDown++;
            return;
        }
        t.state = Task.State.QUEUED;
        queue.add(t);
        startWaiting();
    }

    private void startWaiting() {
        while (running.size() < spec.cores() && !queue.isEmpty()) start(queue.poll());
    }

    private void start(Task t) {
        double now = CloudSim.clock();
        double execMs = execTimeMs(t);
        double dynW = dynamicWPerCore();
        double joules = dynW * execMs / 1000.0;
        double gPerKWh = carbon.marginalIntensity(this, now, currentDrawW(), dynW);
        t.energyJ += joules;
        t.carbonG += CarbonModel.grams(joules, gPerKWh);
        t.execStart = now;
        t.state = Task.State.RUNNING;
        t.nodeEpoch = epoch;
        running.add(t);
        send(getId(), execMs, OffloadEvents.TASK_FINISH, t);
    }

    private void onFinish(Task t) {
        if (t.nodeEpoch != epoch || !running.contains(t)) return;   // voided by a crash
        account();
        running.remove(t);
        t.execEnd = CloudSim.clock();
        t.state = Task.State.EXECUTED;
        executedCount++;
        hourExecuted[hourIndex(CloudSim.clock())]++;
        sendNow(getControllerId(), OffloadEvents.TASK_EXECUTED, t);
        startWaiting();
    }

    private void crash() {
        account();
        for (Task t : running) t.state = Task.State.LOST;
        for (Task t : queue) t.state = Task.State.LOST;
        lostOnCrash += running.size() + queue.size();
        running.clear();
        queue.clear();
        failed = true;
        epoch++;
    }

    private void recover() {
        account();
        failed = false;
        degradeStart = -1;
        degradeFailAt = -1;
        spikeUntil = -1;
    }

    /** Proactive migration: return every task that has not started yet to the controller. */
    private void handBackQueue() {
        if (failed || queue.isEmpty()) return;
        account();
        List<Task> moved = new ArrayList<>(queue);
        queue.clear();
        for (Task t : moved) t.state = Task.State.IN_TRANSIT;
        sendNow(getControllerId(), OffloadEvents.MIGRATED_TASKS, new MigrationBatch(this, moved));
    }

    private void sendHeartbeat() {
        double now = CloudSim.clock();
        double util = running.size() / (double) spec.cores();
        double expected = 38 + 27 * util;
        double excess = 0;
        double errPerMin = 0.3;
        if (degradeStart >= 0 && now >= degradeStart) {        // failing fan / disk / memory
            double p = Rand.clamp((now - degradeStart) / (degradeFailAt - degradeStart), 0, 1);
            excess += 6 + 22 * p;
            errPerMin += 2 + 14 * p * p;
        }
        if (now < spikeUntil) {
            excess += spikeTemp;
            errPerMin += spikeErrRate;
        }
        double temp = expected + excess + rnd.nextGaussian() * 1.5;
        int errors = Rand.poisson(rnd, errPerMin * heartbeatMs / 60_000.0);
        send(getControllerId(), 1.0, OffloadEvents.HEARTBEAT,
                new Telemetry(getId(), now, temp, expected, errors, util, queue.size()));
    }

    // -------------------------------------------------------------- accounting

    /** Integrate energy and carbon since the last call (called on every state change). */
    public void account() {
        double now = CloudSim.clock();
        double dt = now - lastAccounting;
        if (dt <= 0) return;
        int h = hourIndex(lastAccounting);
        if (failed) {
            downtimeMs += dt;
            hourDownMs[h] += dt;
        } else {
            double draw = currentDrawW();
            double joules = draw * dt / 1000.0;
            double grams = CarbonModel.grams(joules, carbon.effectiveIntensity(this, lastAccounting, draw));
            energyJ += joules;
            carbonG += grams;
            busyCoreMs += running.size() * dt;
            hourCarbonG[h] += grams;
            hourBusyCoreMs[h] += running.size() * dt;
        }
        lastAccounting = now;
    }

    /** Size the per-hour timeline arrays (call before the simulation starts). */
    public void initTimeline(int hours) {
        hourBusyCoreMs = new double[hours];
        hourCarbonG = new double[hours];
        hourDownMs = new double[hours];
        hourExecuted = new long[hours];
    }

    private int hourIndex(double tMs) {
        return Math.max(0, Math.min(hourExecuted.length - 1, (int) (tMs / 3_600_000.0)));
    }

    public double[] hourBusyCoreMs() { return hourBusyCoreMs; }
    public double[] hourCarbonG() { return hourCarbonG; }
    public double[] hourDownMs() { return hourDownMs; }
    public long[] hourExecuted() { return hourExecuted; }

    /** Facility power draw right now (W), via the iFogSim linear power model, times PUE. */
    public double currentDrawW() {
        if (failed) return 0;
        double u = Math.min(1.0, running.size() / (double) spec.cores());
        return getHost().getPowerModel().getPower(u) * spec.pue();
    }

    /** Extra facility power of one busy core (W). */
    public double dynamicWPerCore() {
        return (spec.maxW() - spec.idleW()) / spec.cores() * spec.pue();
    }

    public double execTimeMs(Task t) { return t.mi / spec.mipsPerCore() * 1000.0; }

    // ----------------------------------------------------------------- getters

    public NodeSpec spec() { return spec; }
    public Tier tier() { return spec.tier(); }
    public int zone() { return spec.zone(); }
    public boolean isFailed() { return failed; }
    public double energyJ() { return energyJ; }
    public double carbonG() { return carbonG; }
    public double busyCoreMs() { return busyCoreMs; }
    public double downtimeMs() { return downtimeMs; }
    public long executedCount() { return executedCount; }
    public long lostOnCrash() { return lostOnCrash; }
    public long droppedWhileDown() { return droppedWhileDown; }
}
