package carbonfog.sensors;

import carbonfog.model.TaskType;
import carbonfog.sim.CampusScenario;
import carbonfog.util.CsvWriter;
import carbonfog.util.Rand;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stand-alone generator for the simulated sensor streams (no fog simulation):
 * every message of every campus sensor in a time window, as JSON lines
 * (MQTT/AWS-IoT style envelope) and as one flat CSV per sensor type.
 */
public final class SensorStreamExporter {
    private SensorStreamExporter() { }

    /** One sensor message: time, where it came from, and its JSON payload. */
    public record Msg(double timeMs, TaskType type, int zone, String device, Map<String, Object> payload) {
        public String topic() {
            return "campus/" + CampusScenario.ZONES[zone] + "/" + device + "/" + type.shortName();
        }
    }

    /** All messages produced by all 24 campus sensors in [startHour, startHour + minutes). */
    public static List<Msg> generate(long seed, double startHour, double minutes) {
        double from = startHour * 3_600_000, to = from + minutes * 60_000;
        List<Msg> msgs = new ArrayList<>();
        for (int z = 0; z < CampusScenario.ZONES.length; z++) {
            for (int i = 0; i < CampusScenario.ZONE_SENSORS[z].length; i++) {
                TaskType type = CampusScenario.ZONE_SENSORS[z][i];
                String dev = CampusScenario.deviceSpec(z, i, type).name();
                long sensorSeed = seed * 7_919L + 1_000L * z + i;
                SensorDataGenerator gen = new SensorDataGenerator(type, dev, sensorSeed);
                Random arrivals = Rand.rng(sensorSeed + 13);
                double t = from;
                while (true) {
                    double rate = CampusActivity.level(type, t);
                    t += Rand.exponential(arrivals, type.meanIntervalMs / Math.max(rate, 1e-3));
                    if (t >= to) break;
                    msgs.add(new Msg(t, type, z, dev, gen.next(t).payload()));
                }
            }
        }
        msgs.sort(Comparator.comparingDouble(Msg::timeMs));
        return msgs;
    }

    @SuppressWarnings("unchecked")
    public static void export(Path outDir, long seed, double startHour, double minutes) throws IOException {
        List<Msg> msgs = generate(seed, startHour, minutes);
        List<String> jsonl = new ArrayList<>();
        Map<TaskType, List<Map<String, Object>>> perType = new LinkedHashMap<>();
        for (Msg m : msgs) {
            JSONObject env = new JSONObject();
            env.put("topic", m.topic());
            env.put("qos", 1);
            env.put("payload", new JSONObject(m.payload()));
            jsonl.add(env.toJSONString());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time_s", m.timeMs() / 1000.0);
            row.put("zone", CampusScenario.ZONES[m.zone()]);
            row.putAll(m.payload());
            perType.computeIfAbsent(m.type(), k -> new ArrayList<>()).add(row);
        }
        CsvWriter.writeLines(outDir.resolve("sensor_stream.jsonl"), jsonl);
        for (Map.Entry<TaskType, List<Map<String, Object>>> e : perType.entrySet()) {
            CsvWriter.write(outDir.resolve("sensor_" + e.getKey().shortName() + ".csv"), e.getValue());
        }
        System.out.printf("Wrote %d sensor messages (%02.0f:00 + %.0f min) to %s%n",
                msgs.size(), startHour, minutes, outDir.toAbsolutePath());
    }
}
