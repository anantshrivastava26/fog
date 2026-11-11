package carbonfog.model;

/**
 * The five smart-campus IoT workloads. Each sensor produces tasks of one type.
 *
 * baseMI          CPU demand in million instructions (scaled per task by the sensor reading)
 * inputKB         data uploaded if the task is offloaded
 * outputKB        result sent back to the device / actuator
 * deadlineMs      end-to-end latency target (creation -> result back at device)
 * meanIntervalMs  mean time between tasks of one sensor at full campus activity
 * activityFloor   minimum activity level (wearables and meters keep reporting at night)
 */
public enum TaskType {
    VIDEO_ANALYTICS("CCTV frame object detection", 2500, 300, 2, 800, 1000, 0.0),
    HEALTH_ECG("Wearable ECG anomaly detection", 150, 10, 0.5, 150, 1000, 0.5),
    TRAFFIC_CONTROL("Smart traffic-light / parking control", 800, 50, 1, 400, 1000, 0.0),
    AIR_QUALITY("Air-quality fusion and forecast", 200, 5, 0.5, 3000, 5000, 0.0),
    ENERGY_ANALYTICS("Smart-meter batch analytics", 20000, 200, 5, 30000, 20000, 1.0);

    public final String description;
    public final double baseMI;
    public final double inputKB;
    public final double outputKB;
    public final double deadlineMs;
    public final double meanIntervalMs;
    public final double activityFloor;

    TaskType(String description, double baseMI, double inputKB, double outputKB,
             double deadlineMs, double meanIntervalMs, double activityFloor) {
        this.description = description;
        this.baseMI = baseMI;
        this.inputKB = inputKB;
        this.outputKB = outputKB;
        this.deadlineMs = deadlineMs;
        this.meanIntervalMs = meanIntervalMs;
        this.activityFloor = activityFloor;
    }

    public String shortName() {
        return switch (this) {
            case VIDEO_ANALYTICS -> "video";
            case HEALTH_ECG -> "ecg";
            case TRAFFIC_CONTROL -> "traffic";
            case AIR_QUALITY -> "air";
            case ENERGY_ANALYTICS -> "energy";
        };
    }
}
