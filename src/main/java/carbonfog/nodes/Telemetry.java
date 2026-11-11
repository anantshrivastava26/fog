package carbonfog.nodes;

/** Health/load report carried by every heartbeat of a fog node. */
public record Telemetry(int nodeId, double time, double tempC, double expectedTempC,
                        int eccErrors, double utilization, int queueLength) {
}
