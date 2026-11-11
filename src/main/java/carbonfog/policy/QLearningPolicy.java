package carbonfog.policy;

import carbonfog.util.Rand;
import carbonfog.model.Task;
import carbonfog.model.TaskType;
import carbonfog.nodes.ComputeNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Tabular Q-learning offloading agent.
 *
 * <p><b>State</b> (4320 discrete states) = task type (5)
 *   x home-fog load (4: fast / ok / late vs deadline / unavailable)
 *   x best other fog load (4) x cloud load (3)
 *   x carbon regime of home fog (3: solar-powered / partly solar / grid)
 *   x carbon regime of the greenest other fog (3) x can the device meet the deadline alone (2).
 *
 * <p><b>Actions</b> (5) = run locally, home fog, fastest other fog, greenest other fog, cloud.
 * Unavailable actions are masked (e.g. a crashed or drained fog node).
 *
 * <p><b>Reward</b> arrives when the task's result is back ({@link RewardFunction});
 * the update bootstraps on the state observed at that moment (semi-MDP style).
 */
public final class QLearningPolicy implements OffloadPolicy {

    public static final int LOCAL = 0, HOME_FOG = 1, FAST_FOG = 2, GREEN_FOG = 3, CLOUD = 4;
    public static final String[] ACTION_NAMES = {"local", "home-fog", "fastest-other-fog", "greenest-other-fog", "cloud"};
    private static final int A = 5;
    private static final int TYPES = TaskType.values().length;
    public static final int STATES = TYPES * 4 * 4 * 3 * 3 * 3 * 2;

    private final String name;
    private final double[][] q = new double[STATES][A];
    private final int[][] visits = new int[STATES][A];
    private final Random rnd;

    private boolean learning = true;
    private double gamma = 0.3;
    private double epsilonStart = 1.0, epsilonMin = 0.03, epsilonDecayDecisions = 1_200_000;
    private long decisions;
    private long updates;

    public QLearningPolicy(String name, long seed) {
        this.name = name;
        this.rnd = Rand.rng(seed);
    }

    @Override
    public String name() { return name; }

    public void setLearning(boolean on) { this.learning = on; }

    public double epsilon() {
        if (!learning) return 0;
        return Math.max(epsilonMin, epsilonStart * Math.exp(-decisions / epsilonDecayDecisions));
    }

    public long updates() { return updates; }

    // ------------------------------------------------------------- decisions

    private record Encoded(int state, ComputeNode[] targets) { }

    @Override
    public ComputeNode decide(Task task, DecisionContext ctx) {
        Encoded e = encode(task, ctx);
        int a;
        if (learning && rnd.nextDouble() < epsilon()) {
            a = randomValid(e.targets);
        } else {
            a = argmaxVisited(e.state, e.targets);
            if (a < 0) a = fastest(e.targets, ctx);     // never-seen state: fall back to lowest latency
        }
        decisions++;
        task.rlState = e.state;
        task.rlAction = a;
        return e.targets[a];
    }

    @Override
    public void onCompleted(Task task, double reward, DecisionContext next) {
        if (!learning || task.rlState < 0) return;
        Encoded n = encode(task, next);
        int bestNext = argmaxVisited(n.state, n.targets);
        double bootstrap = bestNext < 0 ? 0 : gamma * q[n.state][bestNext];
        update(task.rlState, task.rlAction, reward + bootstrap);
    }

    @Override
    public void onLost(Task task, double penalty) {
        if (!learning || task.rlState < 0) return;
        update(task.rlState, task.rlAction, penalty);   // terminal: no bootstrap
    }

    private void update(int s, int a, double target) {
        int n = ++visits[s][a];
        double alpha = Math.max(0.02, 1.0 / Math.sqrt(n));
        q[s][a] += alpha * (target - q[s][a]);
        updates++;
    }

    private int randomValid(ComputeNode[] targets) {
        int count = 0;
        for (ComputeNode t : targets) if (t != null) count++;
        int k = rnd.nextInt(count);
        for (int i = 0; i < A; i++) if (targets[i] != null && k-- == 0) return i;
        return CLOUD;
    }

    /** Best action among those that are available and have been tried in state s; -1 if none. */
    private int argmaxVisited(int s, ComputeNode[] targets) {
        int best = -1;
        for (int i = 0; i < A; i++) {
            if (targets[i] == null || visits[s][i] == 0) continue;
            if (best < 0 || q[s][i] > q[s][best]) best = i;
        }
        return best;
    }

    private static int fastest(ComputeNode[] targets, DecisionContext ctx) {
        int best = CLOUD;
        for (int i = 0; i < A; i++) {
            if (targets[i] == null) continue;
            if (ctx.estimate(targets[i]).latencyMs() < ctx.estimate(targets[best]).latencyMs()) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------ encoding

    private Encoded encode(Task t, DecisionContext c) {
        ComputeNode[] targets = new ComputeNode[A];
        if (c.fromDevice()) targets[LOCAL] = c.device();
        if (c.usable(c.homeFog())) targets[HOME_FOG] = c.homeFog();
        targets[FAST_FOG] = c.fastestOtherFog();
        targets[GREEN_FOG] = c.greenestOtherFog();
        targets[CLOUD] = c.cloud();

        int type = t.type.ordinal();
        int home = targets[HOME_FOG] == null ? 3 : loadBucket(c.estimate(targets[HOME_FOG]).latencyMs(), t);
        int other = targets[FAST_FOG] == null ? 3 : loadBucket(c.estimate(targets[FAST_FOG]).latencyMs(), t);
        int cloud = loadBucket(c.estimate(c.cloud()).latencyMs(), t);
        int homeCarbon = carbonBucket(c.marginalIntensity(c.homeFog()));
        int greenCarbon = targets[GREEN_FOG] == null ? 2 : carbonBucket(c.marginalIntensity(targets[GREEN_FOG]));
        int localOk = (targets[LOCAL] != null && c.estimate(targets[LOCAL]).latencyMs() <= t.deadlineMs) ? 1 : 0;

        int s = type;
        s = s * 4 + home;
        s = s * 4 + other;
        s = s * 3 + cloud;
        s = s * 3 + homeCarbon;
        s = s * 3 + greenCarbon;
        s = s * 2 + localOk;
        return new Encoded(s, targets);
    }

    private static int loadBucket(double latencyMs, Task t) {
        double r = latencyMs / t.deadlineMs;
        return r < 0.5 ? 0 : (r <= 1.0 ? 1 : 2);
    }

    private static int carbonBucket(double gPerKWh) {
        return gPerKWh < 150 ? 0 : (gPerKWh < 450 ? 1 : 2);
    }

    // --------------------------------------------------------- persistence

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("state,type,home_load,other_load,cloud_load,home_carbon,green_carbon,local_ok,"
                    + "q_local,q_home_fog,q_fastest_other_fog,q_greenest_other_fog,q_cloud,"
                    + "n_local,n_home_fog,n_fastest_other_fog,n_greenest_other_fog,n_cloud,best_action");
            for (int s = 0; s < STATES; s++) {
                int total = 0;
                for (int a = 0; a < A; a++) total += visits[s][a];
                if (total == 0) continue;
                int[] f = decode(s);
                int best = -1;
                for (int a = 0; a < A; a++) if (visits[s][a] > 0 && (best < 0 || q[s][a] > q[s][best])) best = a;
                StringBuilder sb = new StringBuilder();
                sb.append(s).append(',').append(TaskType.values()[f[0]].shortName());
                for (int i = 1; i < f.length; i++) sb.append(',').append(f[i]);
                for (int a = 0; a < A; a++) sb.append(',').append(String.format(java.util.Locale.ROOT, "%.5f", q[s][a]));
                for (int a = 0; a < A; a++) sb.append(',').append(visits[s][a]);
                sb.append(',').append(ACTION_NAMES[best]);
                w.println(sb);
            }
        }
    }

    public void load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            int s = Integer.parseInt(p[0]);
            for (int a = 0; a < A; a++) {
                q[s][a] = Double.parseDouble(p[8 + a]);
                visits[s][a] = Integer.parseInt(p[8 + A + a]);
            }
        }
    }

    private static int[] decode(int s) {
        int localOk = s % 2; s /= 2;
        int green = s % 3; s /= 3;
        int homeC = s % 3; s /= 3;
        int cloud = s % 3; s /= 3;
        int other = s % 4; s /= 4;
        int home = s % 4; s /= 4;
        return new int[]{s, home, other, cloud, homeC, green, localOk};
    }
}
