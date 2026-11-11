package carbonfog.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tabular outputs of one run (each list becomes CSV rows). */
public final class RunResult {
    public final Map<String, Object> summary = new LinkedHashMap<>();
    public final List<Map<String, Object>> hourly = new ArrayList<>();
    public final List<Map<String, Object>> byType = new ArrayList<>();
    public final List<Map<String, Object>> nodes = new ArrayList<>();
    public final List<Map<String, Object>> crashes = new ArrayList<>();
    public final List<Map<String, Object>> healthTrace = new ArrayList<>();
    public final List<String> sensorSample = new ArrayList<>();
    /** Per-hour, per-node state and task flows (used by the web dashboard's campus replay). */
    public final Map<String, Object> timeline = new LinkedHashMap<>();

    public double num(String key) {
        Object v = summary.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
