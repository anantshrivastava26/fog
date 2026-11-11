package carbonfog.sim;

import carbonfog.energy.Region;
import carbonfog.model.NodeSpec;
import carbonfog.model.TaskType;
import carbonfog.model.Tier;

import java.util.List;

import static carbonfog.model.TaskType.AIR_QUALITY;
import static carbonfog.model.TaskType.ENERGY_ANALYTICS;
import static carbonfog.model.TaskType.HEALTH_ECG;
import static carbonfog.model.TaskType.TRAFFIC_CONTROL;
import static carbonfog.model.TaskType.VIDEO_ANALYTICS;

/**
 * The smart-campus topology: 4 zones, each with one fog node and 6 IoT devices,
 * plus one remote cloud region.
 *
 * <pre>
 *                               cloud (renewable-rich region, 35 ms away)
 *                                   |  WAN 100 Mbps
 *        +-----------+--------------+-------------+-----------+
 *      fog-0       fog-1         fog-2          fog-3        (campus LAN 1 Gbps)
 *     library     hostels      engineering    main-gate
 *    (solar)     (solar)       (grid only)     (solar)
 *       |           |              |              |          (zone Wi-Fi AP 50 Mbps)
 *   6 devices   6 devices      6 devices      6 devices
 * </pre>
 */
public final class CampusScenario {
    private CampusScenario() { }

    public static final String[] ZONES = {"library", "hostels", "engineering", "main-gate"};

    /** Which sensor sits on each of the 6 devices of a zone. */
    public static final TaskType[][] ZONE_SENSORS = {
            {VIDEO_ANALYTICS, VIDEO_ANALYTICS, VIDEO_ANALYTICS, AIR_QUALITY, AIR_QUALITY, ENERGY_ANALYTICS},
            {HEALTH_ECG, HEALTH_ECG, HEALTH_ECG, ENERGY_ANALYTICS, ENERGY_ANALYTICS, VIDEO_ANALYTICS},
            {VIDEO_ANALYTICS, VIDEO_ANALYTICS, ENERGY_ANALYTICS, ENERGY_ANALYTICS, TRAFFIC_CONTROL, AIR_QUALITY},
            {TRAFFIC_CONTROL, TRAFFIC_CONTROL, TRAFFIC_CONTROL, VIDEO_ANALYTICS, VIDEO_ANALYTICS, AIR_QUALITY},
    };

    /** Heterogeneous fog nodes: (cores, MIPS/core, idle W, max W, PUE, rooftop PV kWp). */
    public static List<NodeSpec> fogSpecs() {
        return List.of(
                new NodeSpec("fog-0-library", "Library edge server (Xeon-D, rooftop PV)",
                        Tier.FOG, 0, 4, 6000, 60, 150, 1.10, 0.25, Region.CAMPUS_GRID),
                new NodeSpec("fog-1-hostels", "Hostel gateway cluster (4x Jetson, small PV)",
                        Tier.FOG, 1, 4, 3000, 20, 60, 1.00, 0.08, Region.CAMPUS_GRID),
                new NodeSpec("fog-2-engineering", "Engineering micro-datacenter (grid only)",
                        Tier.FOG, 2, 8, 7000, 120, 300, 1.30, 0.0, Region.CAMPUS_GRID),
                new NodeSpec("fog-3-main-gate", "Main-gate roadside unit (rooftop PV)",
                        Tier.FOG, 3, 4, 4500, 40, 110, 1.10, 0.15, Region.CAMPUS_GRID));
    }

    /** Our share of a public-cloud region: only the cores we use draw (attributed) power. */
    public static NodeSpec cloudSpec() {
        return new NodeSpec("cloud", "Public cloud region (renewable-rich grid)",
                Tier.CLOUD, -1, 64, 8000, 0, 64 * 20, 1.15, 0.0, Region.CLOUD_REGION);
    }

    /** Battery IoT device (Raspberry-Pi class); PUE models charger losses. */
    public static NodeSpec deviceSpec(int zone, int index, TaskType type) {
        return new NodeSpec("dev-z" + zone + "-" + index + "-" + type.shortName(),
                "IoT device (" + type.description + ")",
                Tier.DEVICE, zone, 1, 1500, 2, 5, 1.10, 0.0, Region.CAMPUS_GRID);
    }
}
