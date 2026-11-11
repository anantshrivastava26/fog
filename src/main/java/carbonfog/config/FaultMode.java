package carbonfog.config;

/** How the orchestrator reacts to fog-node crashes. */
public enum FaultMode {
    /** Crash detected by missing heartbeats; tasks that were on the node are simply lost. */
    NO_RECOVERY,
    /** Crash detected by missing heartbeats; lost tasks are re-submitted elsewhere. */
    REACTIVE,
    /** Health telemetry predicts the crash: node is drained and its queue migrated before it dies.
     *  Falls back to REACTIVE behaviour for crashes that give no warning. */
    PROACTIVE
}
