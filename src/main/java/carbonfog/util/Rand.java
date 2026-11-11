package carbonfog.util;

import java.util.Random;

/** Small sampling helpers. */
public final class Rand {
    private Rand() { }

    /**
     * A java.util.Random whose seed is scrambled with SplitMix64. Plain
     * {@code new Random(base + i)} gives strongly correlated first draws for
     * neighbouring seeds (e.g. all sensors firing at the same instant).
     */
    public static Random rng(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    public static double exponential(Random r, double mean) {
        return -Math.log(1.0 - r.nextDouble()) * mean;
    }

    public static double uniform(Random r, double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    public static int poisson(Random r, double lambda) {
        if (lambda <= 0) return 0;
        if (lambda > 30) return (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * r.nextGaussian()));
        double l = Math.exp(-lambda), p = 1.0;
        int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > l);
        return k - 1;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
