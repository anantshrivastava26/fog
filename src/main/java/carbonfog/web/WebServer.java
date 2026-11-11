package carbonfog.web;

import carbonfog.config.FaultMode;
import carbonfog.config.SimConfig;
import carbonfog.model.NodeSpec;
import carbonfog.model.TaskType;
import carbonfog.policy.OffloadPolicy;
import carbonfog.policy.PolicyFactory;
import carbonfog.policy.QLearningPolicy;
import carbonfog.sensors.SensorStreamExporter;
import carbonfog.sim.CampusScenario;
import carbonfog.sim.RunResult;
import carbonfog.sim.SimulationRunner;
import carbonfog.util.CsvReader;
import carbonfog.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local web dashboard backend (JDK built-in HTTP server, localhost only).
 *
 * <pre>
 *   GET  /api/info          topology, policies, training status
 *   POST /api/run           start a simulation job  {policies[], faultMode, hours, load, seed, crashesPerNodePerDay}
 *   POST /api/train         start a training job    {days}
 *   GET  /api/jobs/{id}     job progress / result
 *   GET  /api/last-run      result of the most recent simulation (restored on page reload)
 *   GET  /api/experiments   saved results of the full study (results/*.csv)
 *   GET  /api/sensors       simulated sensor messages  ?hour=8&minutes=1
 *   GET  /*                 static frontend from web/
 * </pre>
 *
 * CloudSim keeps global static state, so all simulations run one at a time on a single worker thread.
 */
public final class WebServer {

    private static final class Job {
        final String id;
        final String kind;
        volatile String status = "queued";
        volatile String message = "Waiting for the simulator...";
        volatile double progress;
        volatile String resultJson;
        volatile String error;

        Job(String id, String kind) { this.id = id; this.kind = kind; }
    }

    private final Path webRoot;
    private final Path resultsDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulator");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** Result of the most recent finished simulation, so a page refresh can restore it. */
    private volatile String lastRunJson;
    private final AtomicInteger jobIds = new AtomicInteger();

    private WebServer(Path webRoot, Path resultsDir) {
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.resultsDir = resultsDir;
    }

    public static void start(int port, Path resultsDir, Path webRoot) throws IOException {
        WebServer ws = new WebServer(webRoot, resultsDir);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", ws::api);
        server.createContext("/", ws::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Fog dashboard running at http://localhost:" + port + "/   (Ctrl+C to stop)");
    }

    // ------------------------------------------------------------------ routing

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/info") && method.equals("GET")) {
                send(ex, 200, Json.write(info()));
            } else if (path.equals("/api/run") && method.equals("POST")) {
                send(ex, 200, Json.write(Map.of("jobId", submitRun(body(ex)))));
            } else if (path.equals("/api/train") && method.equals("POST")) {
                send(ex, 200, Json.write(Map.of("jobId", submitTrain(body(ex)))));
            } else if (path.startsWith("/api/jobs/") && method.equals("GET")) {
                Job j = jobs.get(path.substring("/api/jobs/".length()));
                if (j == null) { send(ex, 404, "{\"error\":\"no such job\"}"); return; }
                send(ex, 200, jobJson(j));
            } else if (path.equals("/api/last-run") && method.equals("GET")) {
                send(ex, 200, lastRunJson == null ? "{}" : lastRunJson);
            } else if (path.equals("/api/experiments") && method.equals("GET")) {
                send(ex, 200, Json.write(experiments()));
            } else if (path.equals("/api/sensors") && method.equals("GET")) {
                send(ex, 200, Json.write(sensors(query(ex.getRequestURI()))));
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            send(ex, 400, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String p = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if (p.equals("/")) p = "/index.html";
        Path f = webRoot.resolve(p.substring(1)).normalize();
        if (!f.startsWith(webRoot) || !Files.isRegularFile(f)) {
            send(ex, 404, "not found");
            return;
        }
        String name = f.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png" : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ------------------------------------------------------------------- /info

    private Map<String, Object> info() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", "iFogSim 2 (FogDevice, FogLinearPowerModel) on the CloudSim 7 discrete-event engine");
        m.put("trained", PolicyFactory.isTrained(resultsDir));
        Path q = PolicyFactory.qTablePath(resultsDir, "rl-carbon-aware");
        m.put("trainedAt", Files.exists(q) ? Files.getLastModifiedTime(q).toInstant().toString() : null);
        m.put("experimentsAvailable", Files.exists(resultsDir.resolve("summary_policies.csv")));
        List<Map<String, Object>> pol = new ArrayList<>();
        for (String n : PolicyFactory.NAMES) pol.add(Map.of("name", n, "description", PolicyFactory.DESCRIPTIONS.get(n),
                "needsTraining", n.startsWith("rl-")));
        m.put("policies", pol);
        m.put("faultModes", List.of(FaultMode.values()));
        m.put("zones", List.of(CampusScenario.ZONES));
        List<Map<String, Object>> fogs = new ArrayList<>();
        for (NodeSpec s : CampusScenario.fogSpecs()) fogs.add(spec(s));
        m.put("fogs", fogs);
        m.put("cloud", spec(CampusScenario.cloudSpec()));
        List<Map<String, Object>> devices = new ArrayList<>();
        for (int z = 0; z < CampusScenario.ZONES.length; z++) {
            for (int i = 0; i < CampusScenario.ZONE_SENSORS[z].length; i++) {
                TaskType t = CampusScenario.ZONE_SENSORS[z][i];
                Map<String, Object> d = new LinkedHashMap<>(spec(CampusScenario.deviceSpec(z, i, t)));
                d.put("taskType", t.shortName());
                devices.add(d);
            }
        }
        m.put("devices", devices);
        List<Map<String, Object>> types = new ArrayList<>();
        for (TaskType t : TaskType.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", t.shortName());
            r.put("description", t.description);
            r.put("mi", t.baseMI);
            r.put("inputKB", t.inputKB);
            r.put("deadlineMs", t.deadlineMs);
            r.put("meanIntervalMs", t.meanIntervalMs);
            types.add(r);
        }
        m.put("taskTypes", types);
        return m;
    }

    private static Map<String, Object> spec(NodeSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("label", s.label());
        m.put("tier", s.tier());
        m.put("zone", s.zone());
        m.put("cores", s.cores());
        m.put("mipsPerCore", s.mipsPerCore());
        m.put("idleW", s.idleW());
        m.put("maxW", s.maxW());
        m.put("pue", s.pue());
        m.put("solarKWp", s.solarKWp());
        m.put("region", s.region());
        m.put("ifogsimClass", "org.fog.entities.FogDevice");
        return m;
    }

    // -------------------------------------------------------------------- jobs

    private Job newJob(String kind) {
        Job j = new Job(String.valueOf(jobIds.incrementAndGet()), kind);
        jobs.put(j.id, j);
        return j;
    }

    private String jobJson(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id);
        m.put("kind", j.kind);
        m.put("status", j.status);
        m.put("message", j.message);
        m.put("progress", j.progress);
        m.put("error", j.error);
        String head = Json.write(m);
        if (j.resultJson == null) return head;
        return head.substring(0, head.length() - 1) + ",\"result\":" + j.resultJson + "}";
    }

    @SuppressWarnings("unchecked")
    private String submitRun(JSONObject req) {
        List<String> policies = new ArrayList<>();
        JSONArray arr = (JSONArray) req.getOrDefault("policies", new JSONArray());
        for (Object o : arr) {
            String n = String.valueOf(o);
            if (!PolicyFactory.NAMES.contains(n)) throw new IllegalArgumentException("unknown policy " + n);
            if (!policies.contains(n)) policies.add(n);
        }
        if (policies.isEmpty()) throw new IllegalArgumentException("select at least one policy");
        SimConfig base = new SimConfig();
        base.hours = clamp(num(req, "hours", 24), 1, 72);
        base.loadScale = clamp(num(req, "load", 1.0), 0.2, 3.0);
        base.seed = (long) num(req, "seed", 1000);
        base.failuresPerNodePerDay = clamp(num(req, "crashesPerNodePerDay", 1.0), 0, 6);
        base.failures = base.failuresPerNodePerDay > 0;
        base.faultMode = FaultMode.valueOf(String.valueOf(req.getOrDefault("faultMode", "PROACTIVE")));
        base.traceHealth = true;
        Job job = newJob("run");
        worker.submit(() -> {
            try {
                job.status = "running";
                List<Object> runs = new ArrayList<>();
                for (int i = 0; i < policies.size(); i++) {
                    String name = policies.get(i);
                    job.message = String.format("Simulating %s (%d/%d) - %.0f h of campus time in iFogSim",
                            name, i + 1, policies.size(), base.hours);
                    job.progress = (double) i / policies.size();
                    OffloadPolicy p = PolicyFactory.create(name, resultsDir, base.seed);
                    RunResult r = SimulationRunner.run(base.copy(), p);
                    runs.add(runPayload(r));
                }
                Map<String, Object> result = new LinkedHashMap<>();
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("hours", base.hours);
                cfg.put("load", base.loadScale);
                cfg.put("seed", base.seed);
                cfg.put("crashesPerNodePerDay", base.failuresPerNodePerDay);
                cfg.put("faultMode", base.faultMode);
                result.put("config", cfg);
                result.put("runs", runs);
                job.resultJson = Json.write(result);
                lastRunJson = job.resultJson;
                job.progress = 1;
                job.message = "Done";
                job.status = "done";
            } catch (Throwable t) {
                job.error = t.getMessage() == null ? t.toString() : t.getMessage();
                job.status = "error";
            }
        });
        return job.id;
    }

    private Map<String, Object> runPayload(RunResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", r.summary.get("policy"));
        m.put("summary", r.summary);
        m.put("hourly", r.hourly);
        m.put("byType", r.byType);
        m.put("nodes", r.nodes);
        m.put("crashes", r.crashes);
        m.put("timeline", r.timeline);
        m.put("risk", downsampleRisk(r.healthTrace));
        return m;
    }

    /** Max risk per fog node in 2-minute buckets (keeps the payload small). */
    private static List<Map<String, Object>> downsampleRisk(List<Map<String, Object>> trace) {
        Map<String, Map<Integer, double[]>> byFog = new LinkedHashMap<>();
        for (Map<String, Object> row : trace) {
            String fog = (String) row.get("fog");
            double t = ((Number) row.get("time_h")).doubleValue();
            int bucket = (int) (t * 30);
            double[] cell = byFog.computeIfAbsent(fog, k -> new java.util.TreeMap<>())
                    .computeIfAbsent(bucket, k -> new double[2]);
            cell[0] = Math.max(cell[0], ((Number) row.get("risk")).doubleValue());
            cell[1] = Math.max(cell[1], ((Number) row.get("draining")).doubleValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, double[]>> e : byFog.entrySet()) {
            List<Double> t = new ArrayList<>(), risk = new ArrayList<>(), drain = new ArrayList<>();
            for (Map.Entry<Integer, double[]> b : e.getValue().entrySet()) {
                t.add(b.getKey() / 30.0);
                risk.add(b.getValue()[0]);
                drain.add(b.getValue()[1]);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fog", e.getKey());
            m.put("t", t);
            m.put("risk", risk);
            m.put("draining", drain);
            out.add(m);
        }
        return out;
    }

    private String submitTrain(JSONObject req) {
        int days = (int) clamp(num(req, "days", 12), 1, 40);
        Job job = newJob("train");
        worker.submit(() -> {
            try {
                job.status = "running";
                List<Map<String, Object>> curve = new ArrayList<>();
                String[] agents = {"rl-carbon-aware", "rl-latency-only"};
                double[][] weights = {PolicyFactory.CARBON_AWARE_WEIGHTS, PolicyFactory.LATENCY_ONLY_WEIGHTS};
                int step = 0, total = agents.length * days;
                for (int a = 0; a < agents.length; a++) {
                    QLearningPolicy agent = new QLearningPolicy(agents[a], 1000 + agents[a].length());
                    agent.setLearning(true);
                    for (int d = 0; d < days; d++) {
                        job.message = String.format("Training %s - simulated day %d of %d", agents[a], d + 1, days);
                        job.progress = (double) step++ / total;
                        double[] w = weights[a];
                        SimConfig cfg = new SimConfig().withSeed(1100 + d).withWeights(w[0], w[1], w[2]);
                        RunResult r = SimulationRunner.run(cfg, agent);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("agent", agents[a]);
                        row.put("day", d + 1);
                        row.put("epsilon_end", agent.epsilon());
                        row.put("avg_reward", r.num("avg_reward"));
                        row.put("deadline_met_pct", r.num("deadline_met_pct"));
                        row.put("avg_latency_ms", r.num("avg_latency_ms"));
                        row.put("carbon_mg_per_task", r.num("carbon_mg_per_task"));
                        curve.add(row);
                    }
                    agent.save(PolicyFactory.qTablePath(resultsDir, agents[a]));
                }
                job.resultJson = Json.write(Map.of("curve", curve, "trainedAt", Instant.now().toString()));
                job.progress = 1;
                job.message = "Training complete - Q-tables saved";
                job.status = "done";
            } catch (Throwable t) {
                job.error = t.getMessage() == null ? t.toString() : t.getMessage();
                job.status = "error";
            }
        });
        return job.id;
    }

    // ------------------------------------------------------- experiments / sensors

    private Map<String, Object> experiments() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("summary_policies", "faults_summary", "training_curve", "per_task_type", "hourly", "crashes")) {
            m.put(f, CsvReader.read(resultsDir.resolve(f + ".csv")));
        }
        return m;
    }

    private Map<String, Object> sensors(Map<String, String> q) {
        double hour = clamp(Double.parseDouble(q.getOrDefault("hour", "8")), 0, 23.9);
        double minutes = clamp(Double.parseDouble(q.getOrDefault("minutes", "1")), 0.1, 10);
        long seed = Long.parseLong(q.getOrDefault("seed", "1000"));
        List<SensorStreamExporter.Msg> msgs = SensorStreamExporter.generate(seed, hour, minutes);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SensorStreamExporter.Msg msg : msgs) {
            counts.merge(msg.type().shortName(), 1, Integer::sum);
            if (out.size() >= 3000) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", msg.timeMs() / 1000.0);
            m.put("topic", msg.topic());
            m.put("zone", CampusScenario.ZONES[msg.zone()]);
            m.put("type", msg.type().shortName());
            m.put("device", msg.device());
            m.put("payload", msg.payload());
            out.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hour", hour);
        r.put("minutes", minutes);
        r.put("total", msgs.size());
        r.put("counts", counts);
        r.put("messages", out);
        return r;
    }

    // ----------------------------------------------------------------- helpers

    private static JSONObject body(HttpExchange ex) throws Exception {
        try (InputStream in = ex.getRequestBody()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.isBlank()) return new JSONObject();
            return (JSONObject) new JSONParser().parse(s);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static double num(JSONObject o, String key, double def) {
        Object v = o.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) return Double.parseDouble(s);
        return def;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
