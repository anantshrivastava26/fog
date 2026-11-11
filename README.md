# Carbon- and Latency-Aware Task Offloading in a Fog Network using Reinforcement Learning, with Proactive Migration on Node Failure

A smart-campus fog computing system, **simulated end-to-end in iFogSim 2**:
IoT sensors → fog nodes → cloud. A **Q-learning scheduler** decides, task by task,
whether work runs on the IoT device, on a fog node, or in the cloud. It balances
**latency, energy and carbon footprint**: on-site solar and grid carbon intensity
change through the day, so the best place to run a task changes too. A **health
monitor** watches fog-node telemetry, predicts crashes, and **drains and migrates
work away before the node dies**.

All sensor data, the weather, grid carbon intensity and the hardware faults are **simulated**. No physical devices are needed.

---

## 1. Headline results

The numbers are the mean of 3 simulated evaluation days (≈850,000 tasks per day). Each policy sees the same sensor data, weather and faults.

| Policy | Deadlines met | Mean latency | Carbon / task | Where tasks run (device / fog / cloud) |
|---|---:|---:|---:|---|
| local-only (no offloading) | 31.7 % | unstable queue | 0.823 mg | 100 / 0 / 0 |
| cloud-only | 67.9 % | 555 ms | 0.797 mg | 0 / 0 / 100 |
| nearest-fog (classic fog) | 91.3 % | 484 ms | 1.276 mg | 0 / 100 / 0 |
| random | 74.9 % | 689 ms | 1.127 mg | 17 / 66 / 17 |
| latency-greedy (model-based) | 100.0 % | 337 ms | 1.341 mg | 0 / 97 / 3 |
| carbon-greedy (model-based reference) | 99.8 % | 494 ms | 0.696 mg | 13 / 41 / 46 |
| RL, latency-only reward (ablation) | 99.8 % | 344 ms | 1.342 mg | 0 / 97 / 3 |
| **RL, carbon-aware (proposed)** | **99.8 %** | **464 ms** | **0.751 mg** | 11 / 45 / 44 |

* The carbon-aware RL agent emits **44 % less CO₂ per task than latency-greedy and the latency-only RL agent**, and **41 % less than a classic nearest-fog deployment**, while still meeting 99.8 % of deadlines.
* It learns *when* each tier is clean (Figure 3). Fog nodes do most of the work in the solar hours (09:00–14:00, up to ~75 % of tasks). At night the agent shifts to the renewable-rich cloud region and to the devices themselves.
* **carbon-greedy** is a reference that uses the simulator's own exact latency/carbon formulas, so it knows the system perfectly. The RL agent learns only from reward feedback, gets within 8 % of its carbon, and has lower latency (464 vs 494 ms).
* The latency-only RL agent reaches latency-greedy performance (99.8 % vs 100 % met). This shows the learner works; adding carbon to the reward is what changes its behaviour.

**Fault tolerance.** Each fog node gets 2 crashes per day; the table shows the mean over 3 days.

| Policy / fault handling | Tasks caught on a crashing node (per crash) | Tasks permanently lost (per day) |
|---|---:|---:|
| nearest-fog / no recovery | 18.0 | 95.0 |
| nearest-fog / reactive (heartbeat time-out + resubmit) | 18.0 | 0 |
| nearest-fog / **proactive** (predict + drain + migrate) | **1.6** | 0 |
| rl-carbon-aware / no recovery | 8.7 | 47.7 |
| rl-carbon-aware / reactive | 8.7 | 0 |
| rl-carbon-aware / **proactive** | **1.2** | 0 |

* The predictor caught **every crash that showed warning signs** (4.3 of 5.0 crashes per day), on average **6.2 minutes (373 s) before the crash**. The remaining hits come from sudden crashes such as power cuts, which no telemetry can predict.
* Proactive handling cuts the tasks hit per crash by **86–91 %**. The cost is about **3.3 false alarms per day** across the 4 fog nodes; during each one, a healthy node is briefly drained.
* Reactive recovery saves every task but is slow: detection needs 3 missed heartbeats (6.5 s), so recovered tasks finish about 4 s late and almost all of them miss their deadline.

Figures are in [results/figures/](results/figures/):

| | |
|---|---|
| `fig1_policy_comparison.png` | All policies, 4 metrics |
| `fig2_latency_carbon_tradeoff.png` | Latency vs carbon trade-off |
| `fig3_hourly_carbon_shifting.png` | **Where the RL agent runs tasks over the day, against carbon intensity** |
| `fig3b_solar_and_activity.png` | Simulated solar output and campus workload |
| `fig4_training_curve.png` | Q-learning training progress |
| `fig5_fault_tolerance.png` | No-recovery vs reactive vs proactive |
| `fig6_failure_prediction.png` | Crash-risk score per fog node, with crashes marked |
| `fig7_placement_by_task_type.png` | Placement per task type |

---

## 2. System model

```
                         Cloud region  (64 cores, renewable-rich grid ~90-240 g/kWh, 90 ms away)
                                |   WAN uplink 40 Mbps (shared, FIFO)
     +--------------------------+--------------------------+------------------+
  fog-0 library            fog-1 hostels            fog-2 engineering      fog-3 main-gate      (campus LAN 1 Gbps)
  4x6000 MIPS, 0.25 kWp PV 4x3000 MIPS, 0.08 kWp PV  8x7000 MIPS, grid only  4x4500 MIPS, 0.15 kWp PV
     |  Wi-Fi AP 50 Mbps         |                          |                      |
  6 IoT devices             6 IoT devices              6 IoT devices          6 IoT devices
  (cameras, air quality,    (ECG wearables, smart      (cameras, meters,      (traffic sensors,
   smart meter)              meters, camera)            traffic, air)          cameras, air)
```

**Workloads.** Every sensor reading becomes one task:

| Task | CPU (MI) | Upload | Deadline | Rate per sensor at peak | Syllabus case study |
|---|---:|---:|---:|---|---|
| CCTV object detection | 2500 × people factor | ~300 KB | 800 ms | 1 / s | Smart campus |
| ECG anomaly detection | 150 (240 if irregular) | 10 KB | 150 ms | 1 / s (never below half rate at night) | Wearable sensing / healthcare |
| Traffic-light / parking control | 800 × traffic factor | 50 KB | 400 ms | 1 / s | Smart traffic light |
| Air-quality fusion | 200 | 5 KB | 3 s | 1 / 5 s | Fog analytics |
| Smart-meter analytics | 20000 | 200 KB | 30 s | 1 / 20 s, day and night | Smart home / big-data analytics |

**Simulated sensor data.** `SensorDataGenerator` produces realistic readings for each sensor type. Values follow random walks with daily patterns: heart rate and SpO₂ with 2 % arrhythmia events, PM2.5 with rush-hour peaks, CO₂, vehicle counts and speeds, parking occupancy, kW draw, voltage and power factor. The reading also shapes the task: a crowded frame costs more CPU, and an irregular ECG triggers deeper analysis. Messages use a JSON, MQTT/AWS-IoT-style envelope:

```json
{"topic":"campus/hostels/dev-z1-5-video/video","qos":1,
 "payload":{"device_id":"dev-z1-5-video","seq":1,"ts_ms":28800008,"people_count":19,
            "motion":true,"resolution":"1280x720","frame_kb":290.7}}
```

**Energy and carbon.**
* Each node draws power through iFogSim's `FogLinearPowerModel` (idle → max watts with CPU utilisation), multiplied by its PUE (cooling and power overhead).
* Each node's energy is integrated over time and multiplied by the carbon intensity of its electricity at that moment. Rooftop PV covers the fog node's draw first (41 g/kWh lifecycle), and the rest comes from the campus grid (≈560–780 g/kWh, with an evening peak).
* Network transfers cost energy too: 0.005 kWh/GB on the WAN, 0.001 kWh/GB on the LAN, and 1 W of Wi-Fi radio power on the device.
* Weather varies daily: sky clearness and passing clouds change solar output.

**Failures.** A `FailurePlan` injects fog-node crashes (10–30 min downtime).
* 75 % of crashes are preceded by 3–12 min of degradation: the node runs hotter than its load explains, and corrected memory (ECC) errors climb. The other 25 % are sudden, like a power cut.
* Harmless anomalies (HVAC glitches, transient bit flips) also occur, so the predictor can raise false alarms.

---

## 3. How it works

### Q-learning scheduler (`policy/QLearningPolicy.java`)
* **State** (4,320 discrete states):
  * task type
  * predicted load of the home fog node, the best other fog node and the cloud, each relative to the task's deadline
  * whether the home fog node, and the greenest other fog node, run on solar, partial solar or grid power
  * whether the device alone can meet the deadline
* **Actions:** run locally, home fog node, fastest other fog node, greenest other fog node, or cloud. A crashed or drained node is masked out.
* **Reward**, given when the result reaches the device:
  `r = −(0.3·latency/deadline + 0.3·energy/E_ref + 1.0·carbon/C_ref + 2·[deadline missed])`. A task lost in a crash scores −4.
* **Training:** ε-greedy exploration decaying from 1.0 to 0.03, adaptive learning rate 1/√n, γ = 0.3. It trains for 12 simulated days, each with new weather, traffic and fault seeds; evaluation uses 3 different, unseen days.

### Proactive fault tolerance (`failure/HealthMonitor.java`, `sim/OffloadController.java`)
1. Every fog node sends a heartbeat with telemetry every 2 s.
2. The monitor smooths two signals with moving averages: temperature above what the current load explains, and the ECC error rate. It turns them into a crash risk with a logistic model.
3. **Alarm:** risk ≥ 0.8 for 5 heartbeats in a row. The orchestrator stops routing new work to the node (drain) and **migrates its queued tasks** to other nodes over the LAN.
4. **All clear:** 15 healthy heartbeats in a row put the node back in service.
5. **Fallback for sudden crashes (reactive):** 3 missed heartbeats → node declared down, and every task it held is resubmitted from the device.

---

## 4. Running it

Requirements: **JDK 21+**, because iFogSim 2 uses Java 21 features. **Python 3** with pandas and matplotlib is needed only for the figures.

```powershell
# Windows
.\run.ps1                 # full pipeline: sensor data -> train RL -> compare 8 policies -> fault study (~3 min)
.\run.ps1 quick           # 2-hour smoke test
.\run.ps1 sensors         # export 10 min of simulated sensor streams only
.\run.ps1 eval --eval-days 1
python analysis/plot_results.py results
```
```bash
# Linux / macOS
bash run.sh && python3 analysis/plot_results.py results
```
Options: `--train-days N` (default 12), `--eval-days N` (default 3), `--load X` (workload multiplier), `--seed S`, `--out DIR`.

### Web dashboard (for the demo)

```powershell
.\run.ps1 web            # then open http://localhost:8080   (--port 9000 to change)
```

The dashboard is a local web app. A small Java server (the JDK's built-in HTTP server) runs the real iFogSim simulation on demand, and the browser shows the results. It works offline: Chart.js is bundled in `web/vendor/`.

| Tab | What it shows |
|---|---|
| **Simulate** | Pick policies, fault handling, simulated hours, workload, crash rate and seed, then press *Run*. Shows KPIs, a policy comparison, the hour-by-hour placement against carbon intensity and solar, a per-task-type table, and a crash-risk chart with every crash and its prediction lead time. Can also retrain the RL agents. |
| **Campus replay** | Animated campus topology for the run, hour by hour (*Play* or drag the slider). Shows fog-node CPU load, solar output, crashes, and where tasks flow (device, fog over the LAN, or cloud over the WAN). Click a node to see its iFogSim `FogDevice` specs. |
| **Experiments** | The saved multi-day study from `results/`: policy table, fault-tolerance comparison, training curves. |
| **Sensor data** | Generates the simulated MQTT/JSON sensor stream for any time of day: a message feed, the raw JSON, and readings plotted over time. |
| **How it works** | Architecture, where iFogSim is used, the RL design, fault handling and syllabus mapping. Useful for the viva. |

Demo shortcuts: `http://localhost:8080/?tab=campus&hour=7` opens the replay at 07:00. `?run=1&hours=24` starts a run immediately. `?theme=light` or `?theme=dark` forces the theme for projectors. The last run is restored if the page is reloaded.

The scripts compile with `javac` against the jars in [lib/](lib/), so no Maven or internet access is needed. To open the project in an IDE, mark `src/main/java` as the source root and add `lib/*.jar` as libraries.

### Output files (`results/`)
| File | Content |
|---|---|
| `summary_policies.csv`, `summary_runs.csv` | Per-policy averages and per-day runs (≈45 metrics each) |
| `hourly.csv`, `per_task_type.csv`, `per_node.csv` | Breakdowns by hour, task type and node |
| `training_curve.csv`, `qtable_*.csv` | Learning progress and the learned Q-tables (human-readable states) |
| `faults_summary.csv`, `faults_runs.csv`, `crashes.csv`, `health_trace.csv` | Fault-tolerance study, every crash with prediction lead time, and the risk trace |
| `sensor_data/` | Simulated sensor streams: JSON lines plus one CSV per sensor type |
| `sensor_messages_sample.jsonl` | Messages as they entered the fog orchestrator during one simulated minute |

---

## 5. Code map

```
src/main/java/carbonfog/
  Main.java                      experiment pipeline (train / eval / faults / sensors / quick)
  sim/      SimulationRunner     builds the campus in iFogSim/CloudSim, runs a day, summarises
            CampusScenario       zones, fog/cloud/device hardware, sensor placement
            OffloadController    fog orchestrator: dispatch, rewards, heartbeats, drain & migrate
  nodes/    ComputeNode          extends iFogSim FogDevice: multi-core run queue, energy/carbon, crash, telemetry
  sensors/  SensorDataGenerator  simulated sensor readings (JSON payloads)
            IoTSensor            CloudSim entity emitting tasks (time-varying Poisson arrivals)
  policy/   QLearningPolicy      RL agent;  BaselinePolicies  six baselines;  RewardFunction
  energy/   CarbonModel          grid intensity, solar PV, marginal vs average carbon
  network/  Network, Link        Wi-Fi APs, campus LAN, WAN uplink with FIFO queueing
  failure/  FailurePlan, FailureInjector, HealthMonitor
  web/      WebServer            dashboard backend: REST API + simulation job queue
web/                             dashboard frontend (index.html, app.js, styles.css, vendor/chart.js)
analysis/plot_results.py         figures
lib/                             iFogSim 2 (compiled from Cloudslab/iFogSim@5f68d39, MIT) + dependencies
```

---

## 6. Syllabus coverage

| Unit | Topic in the syllabus | Where it appears |
|---|---|---|
| 1 | Edge vs fog, edge devices, fog nodes, sensors, healthcare scenario | Three-tier campus: devices can compute locally (edge), fog nodes, cloud; ECG wearable workload |
| 2 | **iFogSim**: characteristics and working, modeling and simulation | Every node is an iFogSim `FogDevice` with `PowerHost` + `FogLinearPowerModel`, running on the CloudSim discrete-event engine (`CloudSim.init/startSimulation`, entities, events) |
| 3 | JSON-based protocol, fog interfaces with IoT and cloud, SDN | JSON/MQTT-style sensor messages; device↔fog (Wi-Fi), fog↔fog (LAN), fog↔cloud (WAN) interfaces; centralised SDN-style orchestrator |
| 4 | QoS, **computation offloading**, resource provisioning, trust, case studies (smart traffic light, wearables, smart home) | The RL offloading scheduler with per-task deadlines (QoS); health monitoring and proactive migration; traffic, ECG and smart-meter workloads |
| 5 | Fog analytics vs cloud analytics, data analytics in the fog | Air-quality fusion and smart-meter analytics placed by the scheduler; the results compare fog-side and cloud-side processing |

**AWS mapping** (how the design would be deployed on the AWS services in the syllabus):

| This project | AWS equivalent |
|---|---|
| IoT devices + JSON MQTT messages | AWS IoT Core (MQTT topics `campus/<zone>/<device>/<type>`) |
| Fog nodes running offloaded tasks | AWS IoT Greengrass cores on campus servers; AWS Outposts / Local Zones / Wavelength for operator-hosted edge |
| Cloud tier | EC2 in a low-carbon region |
| Health telemetry + crash-risk alarms | AWS IoT Device Defender metrics and AWS IoT Events detector models |
| Air-quality / smart-meter analytics | AWS IoT Analytics, Amazon Kinesis Data Analytics |

The other tools on the syllabus (Contiki/Cooja, MobFogSim) target MAC/routing and mobility, which this project doesn't need, so it uses iFogSim only.

---

## 7. Assumptions and limitations

* **Accelerated faults:** 1–2 crashes per node per day is far more than real hardware, so that every evaluation day contains several crashes.
* **Execution model:** tasks run first-in-first-out on each node's cores. The cloud is modelled as our attributed share of a big region, so it draws no idle power on our account.
* **iFogSim usage:** iFogSim's application-module placement pipeline (`ModulePlacement`, `Controller`) is static. It is replaced here by a per-task offloading layer built on iFogSim's `FogDevice`, power model and CloudSim event engine, because the decision must be made per task at run time.
* **What the orchestrator knows:** it sees node load because it tracks the work it dispatched. It learns about crashes only through heartbeats.
* **Migration scope:** proactive migration moves queued tasks. Running tasks (mostly under 1 s) are allowed to finish while the node drains. Long-running stateful services are not modelled.
* **Plausible, not measured, parameters:** carbon intensity curves, PV size, power figures and network energy factors are representative values from the literature, not measurements of a specific campus. All of them are in `CampusScenario`, `CarbonModel` and `Network` and can be changed.
