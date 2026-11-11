package carbonfog.sim;

import org.cloudbus.cloudsim.core.CloudSimTags;

/** Custom CloudSim/iFogSim event tags used by the offloading framework. */
public enum OffloadEvents implements CloudSimTags {
    /** sensor -> itself: produce the next reading/task */
    EMIT_TASK,
    /** sensor -> controller: a new task needs a placement decision */
    TASK_SUBMIT,
    /** controller -> node: task input data has arrived */
    TASK_ARRIVE,
    /** node -> itself: a task finished executing on a core */
    TASK_FINISH,
    /** node -> controller: task executed, result on its way back */
    TASK_EXECUTED,
    /** node -> itself: time to emit a heartbeat */
    HEARTBEAT_TICK,
    /** node -> controller: heartbeat carrying health telemetry */
    HEARTBEAT,
    /** controller -> itself: check heartbeat time-outs */
    MONITOR_TICK,
    /** node -> itself: integrate energy/carbon (bounds integration error) */
    ENERGY_TICK,
    /** failure injector -> node: hardware starts degrading (data = crash time) */
    DEGRADE_START,
    /** failure injector -> node: harmless telemetry anomaly (data = Spike) */
    SPIKE_START,
    /** failure injector -> node: crash */
    NODE_FAIL,
    /** failure injector -> node: repaired / rebooted */
    NODE_RECOVER,
    /** controller -> node: hand back all queued (not yet started) tasks */
    MIGRATE_QUEUED,
    /** node -> controller: the handed-back tasks (data = MigrationBatch) */
    MIGRATED_TASKS
}
