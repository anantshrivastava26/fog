package carbonfog.policy;

import carbonfog.util.Rand;
import carbonfog.model.Task;
import carbonfog.nodes.ComputeNode;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Non-learning reference strategies. */
public final class BaselinePolicies {
    private BaselinePolicies() { }

    /** Everything runs on the IoT device itself (falls back to cloud for migrated tasks). */
    public static OffloadPolicy localOnly() {
        return simple("local-only", (t, c) -> c.fromDevice() ? c.device() : c.cloud());
    }

    /** Classic cloud computing: ship everything to the cloud. */
    public static OffloadPolicy cloudOnly() {
        return simple("cloud-only", (t, c) -> c.cloud());
    }

    /** Typical fog deployment: always use the zone's own fog node, nearest other fog if it is down. */
    public static OffloadPolicy nearestFog() {
        return simple("nearest-fog", (t, c) -> {
            if (c.usable(c.homeFog())) return c.homeFog();
            ComputeNode other = c.fastestOtherFog();
            return other != null ? other : c.cloud();
        });
    }

    public static OffloadPolicy random(long seed) {
        Random r = Rand.rng(seed);
        return simple("random", (t, c) -> {
            List<ComputeNode> cand = c.candidates();
            return cand.get(r.nextInt(cand.size()));
        });
    }

    /** Model-based: node with the smallest predicted end-to-end latency (energy/carbon blind). */
    public static OffloadPolicy latencyGreedy() {
        return simple("latency-greedy", (t, c) -> c.candidates().stream()
                .min(Comparator.comparingDouble(n -> c.estimate(n).latencyMs())).orElse(c.cloud()));
    }

    /**
     * Model-based: lowest predicted carbon among nodes predicted to meet the
     * deadline; if none qualifies, the lowest-latency node.
     */
    public static OffloadPolicy carbonGreedy() {
        return simple("carbon-greedy", (t, c) -> {
            ComputeNode best = null;
            for (ComputeNode n : c.candidates()) {
                Estimate e = c.estimate(n);
                if (e.latencyMs() > t.deadlineMs) continue;
                if (best == null || e.carbonG() < c.estimate(best).carbonG()) best = n;
            }
            if (best != null) return best;
            return c.candidates().stream()
                    .min(Comparator.comparingDouble(n -> c.estimate(n).latencyMs())).orElse(c.cloud());
        });
    }

    // ------------------------------------------------------------------

    private interface Rule {
        ComputeNode pick(Task t, DecisionContext c);
    }

    private static OffloadPolicy simple(String name, Rule rule) {
        return new OffloadPolicy() {
            @Override
            public String name() { return name; }

            @Override
            public ComputeNode decide(Task task, DecisionContext ctx) { return rule.pick(task, ctx); }
        };
    }
}
