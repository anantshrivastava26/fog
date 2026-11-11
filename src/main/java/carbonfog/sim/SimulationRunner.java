package carbonfog.sim;

import carbonfog.config.SimConfig;
import carbonfog.energy.CarbonModel;
import carbonfog.energy.Region;
import carbonfog.failure.FailureInjector;
import carbonfog.failure.FailurePlan;
import carbonfog.metrics.Metrics;
import carbonfog.model.NodeSpec;
import carbonfog.model.Task;
import carbonfog.model.TaskType;
import carbonfog.model.Tier;
import carbonfog.network.Network;
import carbonfog.nodes.ComputeNode;
import carbonfog.policy.OffloadPolicy;
import carbonfog.sensors.CampusActivity;
import carbonfog.sensors.IoTSensor;
import carbonfog.sensors.SensorDataGenerator;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the campus in iFogSim/CloudSim, runs one simulation and summarises it. */
public final class SimulationRunner {
    private SimulationRunner() { }

    private static final double HOUR = 3_600_000.0;
    /** Extra simulated time after sensors stop, so in-flight tasks can finish. */
    private static final double DRAIN_MS = 120_000;
    /** An alarm counts as a correct prediction if the node crashes within this window. */
    private static final double PREDICTION_WINDOW_MS = 20 * 60_000;

    public static RunResult run(SimConfig cfg, OffloadPolicy policy) {
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
        Task.resetIds();

        double horizon = cfg.hours * HOUR;
        int days = (int) Math.ceil(cfg.hours / 24.0);
        CarbonModel carbon = new CarbonModel(cfg.seed, days);
        Network network = new Network(CampusScenario.ZONES.length, carbon, cfg.seed);
        Metrics metrics = new Metrics((int) Math.ceil(cfg.hours));

        // ---- physical topology (iFogSim FogDevices) ----
        ComputeNode cloud = ComputeNode.create(CampusScenario.cloudSpec(), carbon, cfg.heartbeatMs, cfg.seed * 97 + 1);
        cloud.setParentId(-1);
        List<ComputeNode> fogs = new ArrayList<>();
        int k = 0;
        for (NodeSpec s : CampusScenario.fogSpecs()) {
            ComputeNode f = ComputeNode.create(s, carbon, cfg.heartbeatMs, cfg.seed * 97 + 10 + k++);
            f.setParentId(cloud.getId());
            f.setUplinkLatency(Network.WAN_LATENCY_MS);
            fogs.add(f);
        }
        List<ComputeNode> devices = new ArrayList<>();
        List<IoTSensor> sensors = new ArrayList<>();
        for (int z = 0; z < CampusScenario.ZONES.length; z++) {
            for (int i = 0; i < CampusScenario.ZONE_SENSORS[z].length; i++) {
                TaskType type = CampusScenario.ZONE_SENSORS[z][i];
                ComputeNode dev = ComputeNode.create(CampusScenario.deviceSpec(z, i, type), carbon, cfg.heartbeatMs,
                        cfg.seed * 97 + 100 + devices.size());
                dev.setParentId(fogs.get(z).getId());
                dev.setUplinkLatency(Network.AP_LATENCY_MS);
                devices.add(dev);
                long sensorSeed = cfg.seed * 7_919L + 1_000L * z + i;
                SensorDataGenerator gen = new SensorDataGenerator(type, dev.getName(), sensorSeed);
                sensors.add(new IoTSensor("sensor-z" + z + "-" + i + "-" + type.shortName(), dev, gen,
                        cfg.loadScale, horizon, sensorSeed + 13));
            }
        }

        FailurePlan plan = FailurePlan.generate(cfg, fogs.size(), horizon);
        new FailureInjector(plan, fogs);

        OffloadController controller = new OffloadController(cfg, fogs, cloud, network, carbon, policy, metrics);
        List<ComputeNode> all = new ArrayList<>(devices);
        all.addAll(fogs);
        all.add(cloud);
        int hours = (int) Math.ceil(cfg.hours);
        for (ComputeNode n : all) {
            n.setControllerId(controller.getId());
            n.initTimeline(hours);
        }
        for (IoTSensor s : sensors) s.setControllerId(controller.getId());

        CloudSim.terminateSimulation(horizon + DRAIN_MS);
        long wall = System.currentTimeMillis();
        CloudSim.startSimulation();
        wall = System.currentTimeMillis() - wall;

        return summarise(cfg, policy, metrics, plan, carbon, network, devices, fogs, cloud, horizon, wall);
    }

    // ---------------------------------------------------------------- summary

    private static RunResult summarise(SimConfig cfg, OffloadPolicy policy, Metrics m, FailurePlan plan,
                                       CarbonModel carbon, Network network, List<ComputeNode> devices,
                                       List<ComputeNode> fogs, ComputeNode cloud, double horizon, long wallMs) {
        RunResult r = new RunResult();
        Metrics.Bucket t = m.total;
        long finished = t.completed + t.dropped;
        Map<String, Object> s = r.summary;
        s.put("policy", policy.name());
        s.put("seed", cfg.seed);
        s.put("fault_mode", cfg.faultMode.name());
        s.put("hours", cfg.hours);
        s.put("load_scale", cfg.loadScale);
        s.put("tasks_generated", t.generated);
        s.put("tasks_completed", t.completed);
        s.put("tasks_dropped", t.dropped);
        s.put("tasks_unfinished", t.generated - finished);
        s.put("deadline_met_pct", pct(t.met, finished));
        s.put("avg_latency_ms", t.completed == 0 ? 0 : t.latencySum / t.completed);
        s.put("p95_latency_ms", m.percentileLatency(0.95));
        s.put("avg_reward", finished == 0 ? 0 : t.reward / finished);
        s.put("share_local_pct", pct(t.tier[Tier.DEVICE.ordinal()], t.completed));
        s.put("share_fog_pct", pct(t.tier[Tier.FOG.ordinal()], t.completed));
        s.put("share_cloud_pct", pct(t.tier[Tier.CLOUD.ordinal()], t.completed));
        s.put("task_energy_kJ", t.energyJ / 1000);
        s.put("task_carbon_g", t.carbonG);
        s.put("carbon_mg_per_task", finished == 0 ? 0 : t.carbonG * 1000 / finished);
        s.put("energy_J_per_task", finished == 0 ? 0 : t.energyJ / finished);

        double devE = 0, devC = 0, fogE = 0, fogC = 0, fogBusy = 0, fogCoreMs = 0;
        for (ComputeNode d : devices) { devE += d.energyJ(); devC += d.carbonG(); }
        for (ComputeNode f : fogs) {
            fogE += f.energyJ(); fogC += f.carbonG();
            fogBusy += f.busyCoreMs(); fogCoreMs += f.spec().cores() * horizon;
        }
        s.put("energy_device_kJ", devE / 1000);
        s.put("energy_fog_kJ", fogE / 1000);
        s.put("energy_cloud_kJ", cloud.energyJ() / 1000);
        s.put("energy_network_kJ", network.energyJ() / 1000);
        s.put("energy_total_kJ", (devE + fogE + cloud.energyJ() + network.energyJ()) / 1000);
        s.put("carbon_device_g", devC);
        s.put("carbon_fog_g", fogC);
        s.put("carbon_cloud_g", cloud.carbonG());
        s.put("carbon_network_g", network.carbonG());
        s.put("carbon_total_g", devC + fogC + cloud.carbonG() + network.carbonG());
        s.put("fog_util_pct", 100 * fogBusy / Math.max(1, fogCoreMs));
        s.put("wan_GB", network.wanGB());

        // ---- failures and prediction quality ----
        long lostOnCrash = 0;
        for (ComputeNode f : fogs) lostOnCrash += f.lostOnCrash() + f.droppedWhileDown();
        int crashes = 0, predictable = 0, predicted = 0;
        double leadSum = 0;
        for (FailurePlan.Crash c : plan.crashes) {
            if (c.failAt() >= horizon) continue;
            crashes++;
            if (c.predictable()) predictable++;
            double first = -1;
            for (double[] a : m.alarms) {
                if ((int) a[0] == c.fogIndex() && a[1] <= c.failAt() && a[1] >= c.failAt() - PREDICTION_WINDOW_MS) {
                    if (first < 0 || a[1] < first) first = a[1];
                }
            }
            if (first >= 0) { predicted++; leadSum += c.failAt() - first; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fog", fogs.get(c.fogIndex()).getName());
            row.put("degrade_start_h", c.predictable() ? c.degradeStart() / HOUR : -1);
            row.put("fail_h", c.failAt() / HOUR);
            row.put("recover_h", c.recoverAt() / HOUR);
            row.put("predictable", c.predictable());
            row.put("predicted", first >= 0);
            row.put("lead_time_s", first >= 0 ? (c.failAt() - first) / 1000 : 0);
            r.crashes.add(row);
        }
        int trueAlarms = 0;
        for (double[] a : m.alarms) {
            boolean hit = false;
            for (FailurePlan.Crash c : plan.crashes) {
                if (c.fogIndex() == (int) a[0] && c.failAt() >= a[1] && c.failAt() <= a[1] + PREDICTION_WINDOW_MS) hit = true;
            }
            if (hit) trueAlarms++;
        }
        s.put("crashes", crashes);
        s.put("crashes_with_warning_signs", predictable);
        s.put("crashes_predicted", predicted);
        s.put("alarms", m.alarms.size());
        s.put("false_alarms", m.alarms.size() - trueAlarms);
        s.put("mean_lead_time_s", predicted == 0 ? 0 : leadSum / predicted / 1000);
        s.put("crash_detections", m.detections);
        s.put("tasks_hit_by_crash", lostOnCrash);
        long affected = m.affectedCompleted + m.affectedDropped;
        s.put("hit_tasks_per_crash", crashes == 0 ? 0 : (double) lostOnCrash / crashes);
        s.put("hit_tasks_met_pct", pct(m.affectedMet, affected));
        s.put("hit_tasks_avg_latency_ms", m.affectedCompleted == 0 ? 0 : m.affectedLatencySum / m.affectedCompleted);
        s.put("tasks_resubmitted", m.resubmissions);
        s.put("tasks_migrated", m.migrations);
        s.put("sim_wall_ms", wallMs);

        // ---- hourly profile ----
        for (int h = 0; h < m.hourly.length; h++) {
            Metrics.Bucket b = m.hourly[h];
            long fin = b.completed + b.dropped;
            double mid = (h + 0.5) * HOUR;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("policy", policy.name());
            row.put("hour", h);
            row.put("tasks", b.generated);
            row.put("deadline_met_pct", pct(b.met, fin));
            row.put("avg_latency_ms", b.completed == 0 ? 0 : b.latencySum / b.completed);
            row.put("carbon_mg_per_task", fin == 0 ? 0 : b.carbonG * 1000 / fin);
            row.put("energy_J_per_task", fin == 0 ? 0 : b.energyJ / fin);
            row.put("share_local_pct", pct(b.tier[0], b.completed));
            row.put("share_fog_pct", pct(b.tier[1], b.completed));
            row.put("share_cloud_pct", pct(b.tier[2], b.completed));
            row.put("campus_grid_g_per_kWh", carbon.gridIntensity(Region.CAMPUS_GRID, mid));
            row.put("cloud_grid_g_per_kWh", carbon.gridIntensity(Region.CLOUD_REGION, mid));
            row.put("solar_irradiance", carbon.solarIrradiance(mid));
            row.put("campus_activity", CampusActivity.level(mid));
            r.hourly.add(row);
        }

        // ---- per task type ----
        for (TaskType tt : TaskType.values()) {
            Metrics.Bucket b = m.byType.get(tt);
            long fin = b.completed + b.dropped;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("policy", policy.name());
            row.put("type", tt.shortName());
            row.put("deadline_ms", tt.deadlineMs);
            row.put("tasks", b.generated);
            row.put("deadline_met_pct", pct(b.met, fin));
            row.put("avg_latency_ms", b.completed == 0 ? 0 : b.latencySum / b.completed);
            row.put("carbon_mg_per_task", fin == 0 ? 0 : b.carbonG * 1000 / fin);
            row.put("share_local_pct", pct(b.tier[0], b.completed));
            row.put("share_fog_pct", pct(b.tier[1], b.completed));
            row.put("share_cloud_pct", pct(b.tier[2], b.completed));
            r.byType.add(row);
        }

        // ---- per node ----
        List<ComputeNode> report = new ArrayList<>(fogs);
        report.add(cloud);
        for (ComputeNode n : report) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("policy", policy.name());
            row.put("node", n.getName());
            row.put("description", n.spec().label());
            row.put("tasks_executed", n.executedCount());
            row.put("utilisation_pct", 100 * n.busyCoreMs() / (n.spec().cores() * horizon));
            row.put("energy_kJ", n.energyJ() / 1000);
            row.put("carbon_g", n.carbonG());
            row.put("downtime_min", n.downtimeMs() / 60_000);
            row.put("tasks_hit_by_crash", n.lostOnCrash() + n.droppedWhileDown());
            r.nodes.add(row);
        }
        Map<String, Object> devRow = new LinkedHashMap<>();
        long devExec = 0;
        double devBusy = 0;
        for (ComputeNode d : devices) { devExec += d.executedCount(); devBusy += d.busyCoreMs(); }
        devRow.put("policy", policy.name());
        devRow.put("node", "all-iot-devices");
        devRow.put("description", devices.size() + " battery IoT devices");
        devRow.put("tasks_executed", devExec);
        devRow.put("utilisation_pct", 100 * devBusy / (devices.size() * horizon));
        devRow.put("energy_kJ", devE / 1000);
        devRow.put("carbon_g", devC);
        devRow.put("downtime_min", 0.0);
        devRow.put("tasks_hit_by_crash", 0);
        r.nodes.add(devRow);

        for (double[] h : m.healthTrace) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time_h", h[0] / HOUR);
            row.put("fog", fogs.get((int) h[1]).getName());
            row.put("risk", h[2]);
            row.put("temp_excess_c", h[3]);
            row.put("ecc_errors_per_min", h[4]);
            row.put("draining", (int) h[5]);
            r.healthTrace.add(row);
        }
        r.sensorSample.addAll(m.sensorSample);
        buildTimeline(r, m, devices, fogs, cloud);
        return r;
    }

    private static void buildTimeline(RunResult r, Metrics m, List<ComputeNode> devices,
                                      List<ComputeNode> fogs, ComputeNode cloud) {
        int hours = m.hourly.length;
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<ComputeNode> infra = new ArrayList<>(fogs);
        infra.add(cloud);
        for (ComputeNode n : infra) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", n.getName());
            row.put("utilPct", hourlyUtil(List.of(n), hours));
            row.put("executed", toList(n.hourExecuted()));
            row.put("carbonG", toList(n.hourCarbonG()));
            List<Double> down = new ArrayList<>();
            for (double d : n.hourDownMs()) down.add(d / 60_000);
            row.put("downMin", down);
            nodes.add(row);
        }
        List<Map<String, Object>> zones = new ArrayList<>();
        for (int z = 0; z < CampusScenario.ZONES.length; z++) {
            List<ComputeNode> zoneDevs = new ArrayList<>();
            for (ComputeNode d : devices) if (d.zone() == z) zoneDevs.add(d);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zone", CampusScenario.ZONES[z]);
            row.put("deviceUtilPct", hourlyUtil(zoneDevs, hours));
            List<List<Long>> flows = new ArrayList<>();
            for (int h = 0; h < hours; h++) flows.add(toList(m.flows[h][z]));
            row.put("flows", flows);   // per hour: [device, fog0, fog1, fog2, fog3, cloud]
            zones.add(row);
        }
        r.timeline.put("hours", hours);
        r.timeline.put("nodes", nodes);
        r.timeline.put("zones", zones);
    }

    private static List<Double> hourlyUtil(List<ComputeNode> group, int hours) {
        List<Double> out = new ArrayList<>();
        for (int h = 0; h < hours; h++) {
            double busy = 0, cap = 0;
            for (ComputeNode n : group) {
                busy += n.hourBusyCoreMs()[h];
                cap += n.spec().cores() * HOUR;
            }
            out.add(cap == 0 ? 0 : 100 * busy / cap);
        }
        return out;
    }

    private static List<Double> toList(double[] a) {
        List<Double> l = new ArrayList<>();
        for (double v : a) l.add(v);
        return l;
    }

    private static List<Long> toList(long[] a) {
        List<Long> l = new ArrayList<>();
        for (long v : a) l.add(v);
        return l;
    }

    private static double pct(long a, long b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }
}
