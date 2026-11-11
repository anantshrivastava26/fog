package carbonfog.sensors;

import carbonfog.energy.CarbonModel;
import carbonfog.model.TaskType;

/** Diurnal campus activity profile (0..1) that modulates sensor arrival rates. */
public final class CampusActivity {
    private CampusActivity() { }

    /** Activity at the start of each hour 00..23 (people on campus, traffic, cameras busy). */
    private static final double[] LEVEL = {
            0.15, 0.12, 0.10, 0.10, 0.10, 0.15, 0.30, 0.55,
            0.85, 1.00, 1.00, 0.95, 0.85, 0.95, 1.00, 1.00,
            0.95, 0.85, 0.75, 0.70, 0.60, 0.45, 0.30, 0.20};

    public static double level(double tMs) {
        double h = CarbonModel.hourOfDay(tMs);
        int i = (int) h;
        double f = h - i;
        return LEVEL[i] * (1 - f) + LEVEL[(i + 1) % 24] * f;
    }

    public static double level(TaskType type, double tMs) {
        return Math.max(type.activityFloor, level(tMs));
    }
}
