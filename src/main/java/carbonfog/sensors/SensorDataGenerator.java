package carbonfog.sensors;

import carbonfog.energy.CarbonModel;
import carbonfog.model.TaskType;
import carbonfog.util.Rand;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Produces synthetic but plausible readings for one physical sensor. State
 * (heart rate, PM2.5 level, meter counter...) evolves as a random walk with
 * diurnal patterns. The reading also shapes the task: a crowded camera frame
 * costs more CPU than an empty one, an irregular ECG triggers deeper analysis.
 */
public final class SensorDataGenerator {

    /** A reading plus how it affects the resulting compute task. */
    public record Reading(Map<String, Object> payload, double miFactor, double inputKB) { }

    private final TaskType type;
    private final String deviceId;
    private final Random rnd;
    private long seq;

    // per-sensor state
    private double heartRate;
    private double pm25;
    private double meterKwh = 0;

    public SensorDataGenerator(TaskType type, String deviceId, long seed) {
        this.type = type;
        this.deviceId = deviceId;
        this.rnd = Rand.rng(seed);
        this.heartRate = 66 + 10 * rnd.nextDouble();
        this.pm25 = 35 + 20 * rnd.nextDouble();
    }

    public Reading next(double tMs) {
        double act = CampusActivity.level(tMs);
        double h = CarbonModel.hourOfDay(tMs);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("device_id", deviceId);
        p.put("seq", ++seq);
        p.put("ts_ms", Math.round(tMs));
        double miFactor = 1.0;
        double kb = type.inputKB;

        switch (type) {
            case VIDEO_ANALYTICS -> {
                int people = Rand.poisson(rnd, 1 + 18 * act);
                boolean motion = people > 0 && rnd.nextDouble() < 0.3 + 0.6 * act;
                kb = type.inputKB * Rand.uniform(rnd, 0.85, 1.15);
                p.put("people_count", people);
                p.put("motion", motion);
                p.put("resolution", "1280x720");
                p.put("frame_kb", round(kb, 1));
                miFactor = 0.8 + 0.02 * Math.min(people, 30);
            }
            case HEALTH_ECG -> {
                double target = 64 + 18 * act;
                heartRate += 0.15 * (target - heartRate) + rnd.nextGaussian() * 1.5;
                boolean irregular = rnd.nextDouble() < 0.02;
                double hr = irregular ? heartRate + 25 + 15 * rnd.nextDouble() : heartRate;
                p.put("heart_rate_bpm", round(hr, 1));
                p.put("spo2_pct", round(Rand.clamp(97.5 + rnd.nextGaussian() * 0.8, 90, 100), 1));
                p.put("rr_irregular", irregular);
                p.put("samples", 250);
                miFactor = irregular ? 1.6 : 1.0;
            }
            case TRAFFIC_CONTROL -> {
                int vehicles = Rand.poisson(rnd, 4 + 46 * act);
                double speed = Rand.clamp(42 - 0.45 * vehicles + rnd.nextGaussian() * 3, 5, 60);
                int parked = (int) Rand.clamp(Math.round(120 * act + rnd.nextGaussian() * 8), 0, 150);
                p.put("vehicle_count", vehicles);
                p.put("avg_speed_kmph", round(speed, 1));
                p.put("parking_occupied", parked);
                p.put("parking_capacity", 150);
                miFactor = 0.6 + vehicles / 60.0;
            }
            case AIR_QUALITY -> {
                // rush-hour peaks around 09:00 and 18:30
                double rush = Math.exp(-Math.pow(h - 9, 2) / 2) + Math.exp(-Math.pow(h - 18.5, 2) / 2);
                double target = 35 + 50 * rush;
                pm25 += 0.1 * (target - pm25) + rnd.nextGaussian() * 2;
                pm25 = Math.max(5, pm25);
                p.put("pm2_5_ugm3", round(pm25, 1));
                p.put("pm10_ugm3", round(pm25 * Rand.uniform(rnd, 1.5, 1.8), 1));
                p.put("co2_ppm", Math.round(420 + 380 * act + rnd.nextGaussian() * 15));
                p.put("temp_c", round(24 + 7 * Math.sin(Math.PI * (h - 8) / 12) + rnd.nextGaussian() * 0.3, 1));
                p.put("humidity_pct", round(Rand.clamp(60 - 15 * Math.sin(Math.PI * (h - 8) / 12) + rnd.nextGaussian() * 2, 20, 95), 1));
            }
            case ENERGY_ANALYTICS -> {
                double interval = (0.4 + 2.6 * act) * Rand.uniform(rnd, 0.85, 1.15);
                meterKwh += interval / 180.0;   // 20 s reporting interval
                p.put("interval_kw", round(interval, 3));
                p.put("cumulative_kwh", round(meterKwh, 3));
                p.put("voltage_v", round(230 + rnd.nextGaussian() * 4, 1));
                p.put("power_factor", round(Rand.uniform(rnd, 0.86, 0.98), 3));
                miFactor = Rand.uniform(rnd, 0.9, 1.1);
            }
        }
        return new Reading(p, miFactor, kb);
    }

    public TaskType type() { return type; }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
