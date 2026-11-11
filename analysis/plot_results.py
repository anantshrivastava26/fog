"""Turn the simulator's CSV output into report figures.

usage:  python analysis/plot_results.py [results_dir]      (default: results)
writes PNGs into <results_dir>/figures/
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

RES = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
FIG = RES / "figures"
FIG.mkdir(parents=True, exist_ok=True)

# ---- validated reference palette (light mode) ----
SURFACE, INK, INK2, MUTED = "#fcfcfb", "#0b0b0b", "#52514e", "#898781"
GRID, AXIS = "#e1e0d9", "#c3c2b7"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
CRITICAL = "#d03b3b"
HILITE, DIM = SERIES[0], "#c3c2b7"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "font.family": ["Segoe UI", "DejaVu Sans", "sans-serif"], "font.size": 10,
    "axes.edgecolor": AXIS, "axes.labelcolor": INK2, "axes.titlecolor": INK,
    "axes.titlesize": 11, "axes.titleweight": "semibold", "axes.titlelocation": "left",
    "xtick.color": MUTED, "ytick.color": INK2, "axes.grid": True, "grid.color": GRID,
    "grid.linewidth": 0.6, "axes.spines.top": False, "axes.spines.right": False,
    "legend.frameon": False, "lines.linewidth": 2,
})

FOCUS = "rl-carbon-aware"


def save(fig, name):
    fig.savefig(FIG / name, dpi=160, bbox_inches="tight")
    plt.close(fig)
    print("wrote", FIG / name)


# ---------------------------------------------------------------- 1. policies
def policy_comparison():
    df = pd.read_csv(RES / "summary_policies.csv").set_index("policy")
    metrics = [
        ("deadline_met_pct", "Deadlines met (%)", "higher is better", None),
        ("avg_latency_ms", "Mean end-to-end latency (ms)", "lower is better", 1500),
        ("carbon_mg_per_task", "Carbon per task (mg CO2e)", "lower is better", None),
        ("energy_J_per_task", "Energy per task (J)", "lower is better", None),
    ]
    order = list(df.index)
    fig, axes = plt.subplots(1, 4, figsize=(15, 4.2), sharey=True)
    for ax, (col, title, sub, cap) in zip(axes, metrics):
        vals = df[col]
        shown = vals.clip(upper=cap) if cap else vals
        colors = [HILITE if p == FOCUS else DIM for p in order]
        ax.barh(order, shown, color=colors, height=0.6, edgecolor=SURFACE, linewidth=2)
        ax.invert_yaxis()
        ax.set_title(f"{title}\n", fontsize=10.5)
        ax.text(0, 1.02, sub, transform=ax.transAxes, color=MUTED, fontsize=8.5)
        ax.grid(axis="y", visible=False)
        xmax = shown.max()
        for i, (p, v) in enumerate(vals.items()):
            label = "unstable queue" if cap and v > cap else (f"{v:.1f}" if v < 100 else f"{v:,.0f}")
            if col == "carbon_mg_per_task":
                label = f"{v:.2f}"
            ax.text(min(shown[p], xmax) + xmax * 0.02, i, label, va="center", fontsize=8.5,
                    color=INK if p == FOCUS else INK2, fontweight="bold" if p == FOCUS else "normal")
        ax.set_xlim(0, xmax * (1.6 if cap else 1.35))
    fig.suptitle("Policy comparison on a simulated smart-campus day (mean of evaluation days)",
                 x=0.01, ha="left", fontsize=12.5, fontweight="semibold", color=INK, y=1.04)
    save(fig, "fig1_policy_comparison.png")


# ------------------------------------------------------------------ 2. pareto
def pareto():
    df = pd.read_csv(RES / "summary_policies.csv")
    df = df[df.policy != "local-only"]
    # hand-placed label offsets so neighbouring points don't collide
    offsets = {"latency-greedy": (0, 12, "center"), "rl-latency-only": (10, -22, "left"),
               "carbon-greedy": (-10, -26, "right"), "cloud-only": (10, 6, "left"),
               FOCUS: (-12, 4, "right")}
    fig, ax = plt.subplots(figsize=(7.5, 5))
    for _, r in df.iterrows():
        focus = r.policy == FOCUS
        ax.scatter(r.avg_latency_ms, r.carbon_mg_per_task, s=90 if focus else 60,
                   color=HILITE if focus else MUTED, edgecolor=SURFACE, linewidth=2, zorder=3)
        dx, dy, ha = offsets.get(r.policy, (8, 4, "left"))
        ax.annotate(f"{r.policy}\n{r.deadline_met_pct:.1f}% deadlines met",
                    (r.avg_latency_ms, r.carbon_mg_per_task), xytext=(dx, dy), textcoords="offset points",
                    ha=ha, fontsize=8.5, color=INK if focus else INK2, fontweight="bold" if focus else "normal")
    ax.set_xlabel("Mean end-to-end latency (ms)")
    ax.set_ylabel("Carbon per task (mg CO2e)")
    ax.set_title("Latency vs carbon trade-off  (down-left is better; local-only off-chart)")
    ax.margins(x=0.25, y=0.15)
    save(fig, "fig2_latency_carbon_tradeoff.png")


# ---------------------------------------------------------- 3. hourly shifting
def hourly():
    df = pd.read_csv(RES / "hourly.csv")
    rl = df[df.policy == FOCUS]
    lg = df[df.policy == "latency-greedy"]
    fig, axes = plt.subplots(3, 1, figsize=(10, 9), sharex=True, gridspec_kw={"height_ratios": [1.3, 1, 0.8]})

    ax = axes[0]
    h = rl.hour
    ax.stackplot(h, rl.share_local_pct, rl.share_fog_pct, rl.share_cloud_pct,
                 colors=SERIES[:3], edgecolor=SURFACE, linewidth=1.5,
                 labels=["IoT device (local)", "Fog nodes", "Cloud"])
    ax.set_ylim(0, 100)
    ax.set_ylabel("Share of tasks (%)")
    ax.set_title(f"Where {FOCUS} runs tasks over the day")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))

    ax = axes[1]
    ax.plot(h, rl.carbon_mg_per_task, color=SERIES[0], label=FOCUS)
    ax.plot(lg.hour, lg.carbon_mg_per_task, color=SERIES[1], label="latency-greedy")
    ax.set_ylabel("mg CO2e per task")
    ax.set_title("Carbon per task by hour")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))

    ax = axes[2]
    ax.plot(h, rl.campus_grid_g_per_kWh, color=SERIES[1], label="Campus grid")
    ax.plot(h, rl.cloud_grid_g_per_kWh, color=SERIES[2], label="Cloud-region grid")
    ax.set_ylabel("g CO2e / kWh")
    ax.set_title("Grid carbon intensity (simulated)")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))
    ax.set_xlabel("Hour of day")
    ax.set_xticks(range(0, 24, 3))
    fig.tight_layout()
    save(fig, "fig3_hourly_carbon_shifting.png")

    fig, ax = plt.subplots(figsize=(10, 2.6))
    ax.plot(h, rl.solar_irradiance * 100, color=SERIES[3], label="Rooftop PV output (% of rated)")
    ax.plot(h, rl.campus_activity * 100, color=SERIES[6], label="Campus activity (% of peak)")
    ax.set_ylabel("%")
    ax.set_xlabel("Hour of day")
    ax.set_xticks(range(0, 24, 3))
    ax.set_title("Simulated drivers: solar generation and IoT workload")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1))
    save(fig, "fig3b_solar_and_activity.png")


# ---------------------------------------------------------------- 4. training
def training():
    df = pd.read_csv(RES / "training_curve.csv")
    fig, axes = plt.subplots(1, 3, figsize=(14, 3.8))
    cols = [("avg_reward", "Average reward (own objective)"), ("deadline_met_pct", "Deadlines met (%)"),
            ("carbon_mg_per_task", "Carbon per task (mg CO2e)")]
    for ax, (c, t) in zip(axes, cols):
        for i, (agent, g) in enumerate(df.groupby("agent", sort=False)):
            ax.plot(g.day, g[c], color=SERIES[i], marker="o", markersize=4, label=agent)
        ax.set_title(t)
        ax.set_xlabel("Training day (new weather, traffic and faults each day)")
    axes[0].legend(loc="lower right")
    fig.suptitle("Q-learning training progress", x=0.01, ha="left", fontsize=12.5, fontweight="semibold", y=1.03)
    fig.tight_layout()
    save(fig, "fig4_training_curve.png")


# ------------------------------------------------------------------ 5. faults
def faults():
    df = pd.read_csv(RES / "faults_summary.csv")
    df[["policy", "mode"]] = df.policy_mode.str.split(" / ", expand=True)
    modes = ["NO_RECOVERY", "REACTIVE", "PROACTIVE"]
    names = {"NO_RECOVERY": "No recovery", "REACTIVE": "Reactive (heartbeat time-out + resubmit)",
             "PROACTIVE": "Proactive (predict + drain + migrate)"}
    policies = list(dict.fromkeys(df.policy))
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.2))
    panels = [("hit_tasks_per_crash", "Tasks caught on a crashing node, per crash", "{:.1f}"),
              ("tasks_dropped", "Tasks permanently lost per day", "{:.0f}")]
    w = 0.26
    for ax, (col, title, fmt) in zip(axes, panels):
        for i, m in enumerate(modes):
            vals = [df[(df.policy == p) & (df["mode"] == m)][col].iloc[0] for p in policies]
            xs = [j + (i - 1) * w for j in range(len(policies))]
            ax.bar(xs, vals, width=w - 0.02, color=SERIES[i], edgecolor=SURFACE, linewidth=2, label=names[m])
            for x, v in zip(xs, vals):
                ax.text(x, v, fmt.format(v), ha="center", va="bottom", fontsize=8.5, color=INK2)
        ax.set_xticks(range(len(policies)), policies)
        ax.set_title(title)
        ax.grid(axis="x", visible=False)
    axes[1].legend(loc="upper right")
    fig.suptitle("Fault tolerance with 2 crashes per fog node per day", x=0.01, ha="left",
                 fontsize=12.5, fontweight="semibold", y=1.03)
    fig.tight_layout()
    save(fig, "fig5_fault_tolerance.png")


# ------------------------------------------------------------ 6. health trace
def health():
    tr = pd.read_csv(RES / "health_trace.csv")
    if tr.empty:
        return
    crashes = pd.read_csv(RES / "crashes.csv")
    seed = crashes.seed.min() if not crashes.empty else None
    cr = crashes[crashes.seed == seed] if seed is not None else crashes
    fogs = list(dict.fromkeys(tr.fog))
    fig, axes = plt.subplots(len(fogs), 1, figsize=(12, 1.9 * len(fogs)), sharex=True, sharey=True)
    for ax, f in zip(axes, fogs):
        g = tr[tr.fog == f].copy()
        # break the line while the node is down (no heartbeats -> no samples)
        gap = g.time_h.diff() > 60 / 3600
        g.loc[gap, "risk"] = float("nan")
        ax.plot(g.time_h, g.risk, color=SERIES[0], linewidth=1.2)
        ax.axhline(0.8, color=MUTED, linewidth=0.8, linestyle="--")
        last_x = -10
        for _, c in cr[cr.fog == f].sort_values("fail_h").iterrows():
            ax.axvspan(c.fail_h, c.recover_h, color=CRITICAL, alpha=0.15, linewidth=0)
            tag = "predicted" if c.predicted else ("sudden" if not c.predictable else "missed")
            x = max(c.fail_h, last_x + 1.2)
            ax.text(x, 1.05, tag, fontsize=8, color=CRITICAL)
            last_x = x
            if c.predictable:
                ax.axvline(c.degrade_start_h, color=SERIES[3], linewidth=1)
        ax.set_ylim(0, 1.2)
        ax.set_ylabel("risk")
        ax.set_title(f, fontsize=9.5)
    axes[-1].set_xlabel("Hour of day")
    axes[-1].set_xticks(range(0, 25, 2))
    fig.suptitle("Crash-risk score from heartbeat telemetry (dashed = alarm threshold 0.8, yellow = degradation "
                 "starts, red band = node down; label = crash was predicted / sudden)", x=0.01, ha="left",
                 fontsize=11, fontweight="semibold")
    fig.tight_layout()
    save(fig, "fig6_failure_prediction.png")


# ------------------------------------------------------------ 7. per task type
def per_type():
    df = pd.read_csv(RES / "per_task_type.csv")
    sel = ["latency-greedy", "carbon-greedy", FOCUS]
    df = df[df.policy.isin(sel)]
    types = list(dict.fromkeys(df.type))
    fig, axes = plt.subplots(1, len(sel), figsize=(14, 3.6), sharey=True)
    for ax, p in zip(axes, sel):
        g = df[df.policy == p].set_index("type").loc[types]
        left = [0] * len(types)
        for i, (col, lab) in enumerate([("share_local_pct", "Device"), ("share_fog_pct", "Fog"),
                                        ("share_cloud_pct", "Cloud")]):
            ax.barh(types, g[col], left=left, color=SERIES[i], height=0.6, edgecolor=SURFACE, linewidth=2,
                    label=lab)
            left = [a + b for a, b in zip(left, g[col])]
        for j, t in enumerate(types):
            ax.text(101, j, f"{g.loc[t, 'deadline_met_pct']:.1f}% met", va="center", fontsize=8.5, color=INK2)
        ax.set_xlim(0, 125)
        ax.invert_yaxis()
        ax.set_title(p)
        ax.grid(axis="y", visible=False)
    axes[0].legend(loc="lower left", bbox_to_anchor=(0, -0.35), ncol=3)
    fig.suptitle("Placement by task type (% of tasks)", x=0.01, ha="left", fontsize=12.5,
                 fontweight="semibold", y=1.03)
    save(fig, "fig7_placement_by_task_type.png")


for fn in (policy_comparison, pareto, hourly, training, faults, health, per_type):
    try:
        fn()
    except FileNotFoundError as e:
        print("skipped", fn.__name__, "-", e)
