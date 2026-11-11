package carbonfog.policy;

/** Predicted outcome of running a task on a given node, as seen at decision time. */
public record Estimate(double latencyMs, double energyJ, double carbonG) {
}
