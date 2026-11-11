package carbonfog.model;

import carbonfog.nodes.ComputeNode;

import java.util.Map;

/** One unit of offloadable work created from a sensor reading. */
public final class Task {

    public enum State { CREATED, IN_TRANSIT, QUEUED, RUNNING, EXECUTED, DONE, LOST, DROPPED }

    private static long nextId = 1;

    public static void resetIds() { nextId = 1; }

    public final long id;
    public final TaskType type;
    /** The IoT device whose sensor produced the task (also the "local" execution target). */
    public final ComputeNode device;
    public final int zone;
    public final double createdAt;
    public final double mi;
    public final double inputKB;
    public final double outputKB;
    public final double deadlineMs;
    /** The simulated sensor message (serialisable to JSON). */
    public final Map<String, Object> payload;

    // ----- runtime bookkeeping -----
    public State state = State.CREATED;
    public ComputeNode target;
    public int attempts;
    public boolean migrated;
    public boolean resubmitted;
    public double execStart = -1;
    public double execEnd = -1;
    /** Attributed energy (J) and carbon (g CO2e): execution + network transfers. */
    public double energyJ;
    public double carbonG;
    /** Epoch of the node when execution started; a crash bumps the epoch and voids the run. */
    public int nodeEpoch;
    /** RL bookkeeping: state/action of the most recent decision for this task. */
    public int rlState = -1;
    public int rlAction = -1;

    public Task(TaskType type, ComputeNode device, int zone, double createdAt, double mi,
                double inputKB, Map<String, Object> payload) {
        this.id = nextId++;
        this.type = type;
        this.device = device;
        this.zone = zone;
        this.createdAt = createdAt;
        this.mi = mi;
        this.inputKB = inputKB;
        this.outputKB = type.outputKB;
        this.deadlineMs = type.deadlineMs;
        this.payload = payload;
    }
}
