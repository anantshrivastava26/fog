package carbonfog;

import carbonfog.config.FaultMode;
import carbonfog.config.SimConfig;
import carbonfog.policy.BaselinePolicies;
import carbonfog.policy.OffloadPolicy;
import carbonfog.policy.PolicyFactory;
import carbonfog.policy.QLearningPolicy;
import carbonfog.web.WebServer;
import carbonfog.sensors.SensorStreamExporter;
import carbonfog.sim.RunResult;
import carbonfog.sim.SimulationRunner;
import carbonfog.util.CsvWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 *   all       train both RL agents, compare all policies, run the fault-tolerance study (default)
 *   train     train the RL agents only (writes Q-tables)
 *   eval      compare policies using previously trained Q-tables
 *   faults    fault-tolerance study only (uses trained carbon-aware Q-table)
 *   sensors   export simulated sensor streams (JSON lines + CSV)
 *   quick     short smoke test (2 h, a few policies)
 *   web       interactive dashboard at http://localhost:8080 (--port to change)
 *
 * options:  --train-days N (default 12)   --eval-days N (default 3)   --out DIR (default results)
 *           --load X (default 1.0)        --seed S
 * </pre>
 */
public final class Main {

    private static int trainDays = 12;
    private static int evalDays = 3;
    private static Path out = Path.of("results");
    private static double load = 1.0;
    private static long baseSeed = 1000;
    private static double quickHours = 2;
    private static int port = 8080;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String mode = args.length > 0 && !args[0].startsWith("--") ? args[0] : "all";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--train-days" -> trainDays = Integer.parseInt(args[i + 1]);
                case "--eval-days" -> evalDays = Integer.parseInt(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                case "--load" -> load = Double.parseDouble(args[i + 1]);
                case "--seed" -> baseSeed = Long.parseLong(args[i + 1]);
                case "--hours" -> quickHours = Double.parseDouble(args[i + 1]);
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                default -> { }
            }
        }
        Files.createDirectories(out);
        if (mode.equals("web")) {
            WebServer.start(port, out, Path.of("web"));
            return;                                      // server threads keep the JVM alive
        }
        long t0 = System.currentTimeMillis();
        switch (mode) {
            case "quick" -> quick();
            case "train" -> train();
            case "eval" -> evaluate();
            case "faults" -> faults();
            case "sensors" -> SensorStreamExporter.export(out.resolve("sensor_data"), baseSeed, 8, 10);
            case "all" -> {
                SensorStreamExporter.export(out.resolve("sensor_data"), baseSeed, 8, 10);
                train();
                evaluate();
                faults();
            }
            default -> {
                System.err.println("unknown mode " + mode + " (use all|train|eval|faults|sensors|quick)");
                System.exit(2);
            }
        }
        System.out.printf("%nDone in %.1f s. Results in %s%n", (System.currentTimeMillis() - t0) / 1000.0,
                out.toAbsolutePath());
    }

    private static SimConfig baseConfig() {
        SimConfig c = new SimConfig();
        c.loadScale = load;
        c.seed = baseSeed;
        return c;
    }

    // ------------------------------------------------------------------ train

    /** Reward weights of the two RL variants: carbon-aware (default) and latency-only (ablation). */
    private static final double[] CARBON_AWARE = PolicyFactory.CARBON_AWARE_WEIGHTS;
    private static final double[] LATENCY_ONLY = PolicyFactory.LATENCY_ONLY_WEIGHTS;

    private static void train() throws IOException {
        List<Map<String, Object>> curve = new ArrayList<>();
        trainAgent("rl-carbon-aware", CARBON_AWARE, curve);
        trainAgent("rl-latency-only", LATENCY_ONLY, curve);
        CsvWriter.write(out.resolve("training_curve.csv"), curve);
    }

    private static QLearningPolicy trainAgent(String name, double[] w, List<Map<String, Object>> curve)
            throws IOException {
        System.out.printf("%n== Training %s for %d simulated days (weights L=%.1f E=%.1f C=%.1f) ==%n",
                name, trainDays, w[0], w[1], w[2]);
        QLearningPolicy agent = new QLearningPolicy(name, baseSeed + name.length());
        agent.setLearning(true);
        for (int d = 0; d < trainDays; d++) {
            // training days use different seeds (weather, traffic, faults) from the evaluation days
            SimConfig cfg = baseConfig().withSeed(baseSeed + 100 + d).withWeights(w[0], w[1], w[2]);
            RunResult r = SimulationRunner.run(cfg, agent);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", name);
            row.put("day", d + 1);
            row.put("epsilon_end", agent.epsilon());
            row.put("avg_reward", r.num("avg_reward"));
            row.put("deadline_met_pct", r.num("deadline_met_pct"));
            row.put("avg_latency_ms", r.num("avg_latency_ms"));
            row.put("carbon_mg_per_task", r.num("carbon_mg_per_task"));
            row.put("energy_J_per_task", r.num("energy_J_per_task"));
            curve.add(row);
            System.out.printf("  day %2d  eps=%.3f  reward=%7.3f  met=%5.1f%%  lat=%6.0f ms  CO2=%6.3f mg/task  (%.1fs)%n",
                    d + 1, agent.epsilon(), r.num("avg_reward"), r.num("deadline_met_pct"), r.num("avg_latency_ms"),
                    r.num("carbon_mg_per_task"), r.num("sim_wall_ms") / 1000);
        }
        agent.save(out.resolve("qtable_" + name + ".csv"));
        return agent;
    }

    private static QLearningPolicy loadAgent(String name) throws IOException {
        QLearningPolicy agent = new QLearningPolicy(name, baseSeed);
        Path p = out.resolve("qtable_" + name + ".csv");
        if (!Files.exists(p)) throw new IllegalStateException("missing " + p + " - run 'train' first");
        agent.load(p);
        agent.setLearning(false);
        return agent;
    }

    // --------------------------------------------------------------- evaluate

    private static void evaluate() throws IOException {
        List<OffloadPolicy> policies = List.of(
                BaselinePolicies.localOnly(),
                BaselinePolicies.cloudOnly(),
                BaselinePolicies.nearestFog(),
                BaselinePolicies.random(baseSeed),
                BaselinePolicies.latencyGreedy(),
                BaselinePolicies.carbonGreedy(),
                loadAgent("rl-latency-only"),
                loadAgent("rl-carbon-aware"));
        System.out.printf("%n== Policy comparison: %d policies x %d evaluation days (PROACTIVE fault handling) ==%n",
                policies.size(), evalDays);
        List<Map<String, Object>> summary = new ArrayList<>(), hourly = new ArrayList<>(), types = new ArrayList<>(),
                nodes = new ArrayList<>();
        for (OffloadPolicy p : policies) {
            for (int d = 0; d < evalDays; d++) {
                SimConfig cfg = baseConfig().withSeed(baseSeed + d);
                if (d == 0 && p.name().equals("rl-carbon-aware")) {
                    cfg.sampleSensorFromMs = 8 * 3_600_000;
                    cfg.sampleSensorToMs = cfg.sampleSensorFromMs + 60_000;
                }
                RunResult r = SimulationRunner.run(cfg, p);
                summary.add(r.summary);
                if (d == 0) {
                    hourly.addAll(r.hourly);
                    types.addAll(r.byType);
                    nodes.addAll(r.nodes);
                }
                if (!r.sensorSample.isEmpty()) CsvWriter.writeLines(out.resolve("sensor_messages_sample.jsonl"), r.sensorSample);
                printRow(r);
            }
        }
        CsvWriter.write(out.resolve("summary_runs.csv"), summary);
        CsvWriter.write(out.resolve("summary_policies.csv"), average(summary, "policy"));
        CsvWriter.write(out.resolve("hourly.csv"), hourly);
        CsvWriter.write(out.resolve("per_task_type.csv"), types);
        CsvWriter.write(out.resolve("per_node.csv"), nodes);
    }

    // ----------------------------------------------------------------- faults

    private static void faults() throws IOException {
        List<OffloadPolicy> policies = List.of(BaselinePolicies.nearestFog(), loadAgent("rl-carbon-aware"));
        System.out.printf("%n== Fault-tolerance study: 2 crashes/node/day, %d days ==%n", evalDays);
        List<Map<String, Object>> rows = new ArrayList<>(), crashes = new ArrayList<>(), trace = new ArrayList<>();
        for (OffloadPolicy p : policies) {
            for (FaultMode mode : FaultMode.values()) {
                for (int d = 0; d < evalDays; d++) {
                    SimConfig cfg = baseConfig().withSeed(baseSeed + d).withFaultMode(mode);
                    cfg.failuresPerNodePerDay = 2.0;
                    boolean first = d == 0 && mode == FaultMode.PROACTIVE && p == policies.get(policies.size() - 1);
                    cfg.traceHealth = first;
                    RunResult r = SimulationRunner.run(cfg, p);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("policy_mode", p.name() + " / " + mode.name());
                    row.putAll(r.summary);
                    rows.add(row);
                    if (mode == FaultMode.PROACTIVE && p == policies.get(0)) {
                        for (Map<String, Object> c : r.crashes) {
                            Map<String, Object> crow = new LinkedHashMap<>();
                            crow.put("seed", cfg.seed);
                            crow.putAll(c);
                            crashes.add(crow);
                        }
                    }
                    trace.addAll(r.healthTrace);
                    printRow(r);
                }
            }
        }
        CsvWriter.write(out.resolve("faults_runs.csv"), rows);
        CsvWriter.write(out.resolve("faults_summary.csv"), average(rows, "policy_mode"));
        CsvWriter.write(out.resolve("crashes.csv"), crashes);
        CsvWriter.write(out.resolve("health_trace.csv"), trace);
    }

    // ------------------------------------------------------------------ quick

    private static void quick() throws IOException {
        SimConfig cfg = baseConfig();
        cfg.hours = quickHours;
        List<Map<String, Object>> rows = new ArrayList<>(), hourly = new ArrayList<>(), types = new ArrayList<>(),
                nodes = new ArrayList<>();
        for (OffloadPolicy p : List.of(BaselinePolicies.localOnly(), BaselinePolicies.nearestFog(),
                BaselinePolicies.cloudOnly(), BaselinePolicies.latencyGreedy(), BaselinePolicies.carbonGreedy())) {
            RunResult r = SimulationRunner.run(cfg, p);
            rows.add(r.summary);
            hourly.addAll(r.hourly);
            types.addAll(r.byType);
            nodes.addAll(r.nodes);
            printRow(r);
        }
        Path q = out.resolve("quick");
        CsvWriter.write(q.resolve("summary.csv"), rows);
        CsvWriter.write(q.resolve("hourly.csv"), hourly);
        CsvWriter.write(q.resolve("per_task_type.csv"), types);
        CsvWriter.write(q.resolve("per_node.csv"), nodes);
    }

    // ---------------------------------------------------------------- helpers

    private static void printRow(RunResult r) {
        System.out.printf("  %-16s %-11s seed=%d  tasks=%7.0f  met=%5.1f%%  lat=%6.0f ms  p95=%6.0f  "
                        + "CO2=%6.3f mg/task  E=%5.2f J/task  L/F/C=%3.0f/%3.0f/%3.0f%%  drop=%4.0f  mig=%4.0f  "
                        + "crash=%.0f pred=%.0f alarms=%.0f hit=%.0f  (%.1fs)%n",
                r.summary.get("policy"), r.summary.get("fault_mode"), (Long) r.summary.get("seed"),
                r.num("tasks_generated"), r.num("deadline_met_pct"), r.num("avg_latency_ms"), r.num("p95_latency_ms"),
                r.num("carbon_mg_per_task"), r.num("energy_J_per_task"),
                r.num("share_local_pct"), r.num("share_fog_pct"), r.num("share_cloud_pct"),
                r.num("tasks_dropped"), r.num("tasks_migrated"), r.num("crashes"), r.num("crashes_predicted"),
                r.num("alarms"), r.num("tasks_hit_by_crash"), r.num("sim_wall_ms") / 1000);
    }

    /** Average numeric columns of rows grouped by a key column. */
    private static List<Map<String, Object>> average(List<Map<String, Object>> rows, String key) {
        Map<Object, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) groups.computeIfAbsent(r.get(key), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> outRows = new ArrayList<>();
        for (Map.Entry<Object, List<Map<String, Object>>> g : groups.entrySet()) {
            Map<String, Object> avg = new LinkedHashMap<>();
            avg.put(key, g.getKey());
            avg.put("runs", g.getValue().size());
            for (String col : g.getValue().get(0).keySet()) {
                if (col.equals(key) || col.equals("seed")) continue;
                Object first = g.getValue().get(0).get(col);
                if (first instanceof Number) {
                    double s = 0;
                    for (Map<String, Object> r : g.getValue()) s += ((Number) r.get(col)).doubleValue();
                    avg.put(col, s / g.getValue().size());
                } else {
                    avg.put(col, first);
                }
            }
            outRows.add(avg);
        }
        return outRows;
    }
}
