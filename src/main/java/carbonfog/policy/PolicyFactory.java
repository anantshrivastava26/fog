package carbonfog.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates policies by name (used by the CLI and the web dashboard). */
public final class PolicyFactory {
    private PolicyFactory() { }

    public static final List<String> NAMES = List.of(
            "local-only", "cloud-only", "nearest-fog", "random",
            "latency-greedy", "carbon-greedy", "rl-latency-only", "rl-carbon-aware");

    public static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        DESCRIPTIONS.put("local-only", "Everything runs on the IoT device (no offloading)");
        DESCRIPTIONS.put("cloud-only", "Everything is shipped to the cloud");
        DESCRIPTIONS.put("nearest-fog", "Classic fog: always the zone's own fog node");
        DESCRIPTIONS.put("random", "Random node among those available");
        DESCRIPTIONS.put("latency-greedy", "Model-based: lowest predicted latency");
        DESCRIPTIONS.put("carbon-greedy", "Model-based: lowest predicted carbon that meets the deadline");
        DESCRIPTIONS.put("rl-latency-only", "Q-learning agent rewarded for latency only (ablation)");
        DESCRIPTIONS.put("rl-carbon-aware", "Q-learning agent rewarded for latency + energy + carbon (proposed)");
    }

    /** Reward weights {latency, energy, carbon} used to train the two RL agents. */
    public static final double[] CARBON_AWARE_WEIGHTS = {0.3, 0.3, 1.0};
    public static final double[] LATENCY_ONLY_WEIGHTS = {1.0, 0.0, 0.0};

    public static Path qTablePath(Path resultsDir, String agent) {
        return resultsDir.resolve("qtable_" + agent + ".csv");
    }

    public static boolean isTrained(Path resultsDir) {
        return Files.exists(qTablePath(resultsDir, "rl-carbon-aware"))
                && Files.exists(qTablePath(resultsDir, "rl-latency-only"));
    }

    public static OffloadPolicy create(String name, Path resultsDir, long seed) throws IOException {
        return switch (name) {
            case "local-only" -> BaselinePolicies.localOnly();
            case "cloud-only" -> BaselinePolicies.cloudOnly();
            case "nearest-fog" -> BaselinePolicies.nearestFog();
            case "random" -> BaselinePolicies.random(seed);
            case "latency-greedy" -> BaselinePolicies.latencyGreedy();
            case "carbon-greedy" -> BaselinePolicies.carbonGreedy();
            case "rl-latency-only", "rl-carbon-aware" -> {
                Path p = qTablePath(resultsDir, name);
                if (!Files.exists(p)) throw new IllegalStateException(name + " is not trained yet (missing " + p + ")");
                QLearningPolicy agent = new QLearningPolicy(name, seed);
                agent.load(p);
                agent.setLearning(false);
                yield agent;
            }
            default -> throw new IllegalArgumentException("unknown policy " + name);
        };
    }
}
