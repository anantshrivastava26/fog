package carbonfog.network;

import carbonfog.util.Rand;
import carbonfog.energy.CarbonModel;
import carbonfog.energy.Region;
import carbonfog.model.Task;
import carbonfog.model.Tier;
import carbonfog.nodes.ComputeNode;

import java.util.Random;

/**
 * Campus network:  device --(zone Wi-Fi AP)--> zone fog node --(campus LAN)--> other fog nodes
 *                                                              \--(WAN uplink)--> cloud
 * Each AP, the LAN backbone and the WAN uplink are shared FIFO links.
 */
public final class Network {

    public static final double AP_MBPS = 50, AP_LATENCY_MS = 3;
    public static final double LAN_MBPS = 1000, LAN_LATENCY_MS = 1;
    /** Share of the campus internet uplink available to IoT traffic, and one-way delay to the
     *  (distant, renewable-rich) cloud region. */
    public static final double WAN_MBPS = 40, WAN_LATENCY_MS = 90, WAN_JITTER_MS = 10;

    /** Radio power of an IoT device while transmitting over Wi-Fi. */
    public static final double DEVICE_TX_W = 1.0;
    /** Network energy intensity (kWh per GB) of the campus LAN and the internet path to the cloud. */
    public static final double LAN_KWH_PER_GB = 0.001, WAN_KWH_PER_GB = 0.005;

    public record Transfer(double arrivalTime, double energyJ, double carbonG) { }

    private final Link[] aps;
    private final Link lan = new Link("campus-lan", LAN_MBPS, LAN_LATENCY_MS);
    private final Link wan = new Link("wan-uplink", WAN_MBPS, WAN_LATENCY_MS);
    private final CarbonModel carbon;
    private final Random rnd;

    private double energyJ;
    private double carbonG;

    public Network(int zones, CarbonModel carbon, long seed) {
        this.aps = new Link[zones];
        for (int z = 0; z < zones; z++) aps[z] = new Link("ap-" + z, AP_MBPS, AP_LATENCY_MS);
        this.carbon = carbon;
        this.rnd = Rand.rng(seed * 31 + 5);
    }

    /**
     * Move a task's input data from {@code from} to {@code to}.
     *
     * @param commit true = actually book the links (dispatch); false = what-if estimate
     */
    public Transfer upload(Task t, ComputeNode from, ComputeNode to, double now, boolean commit) {
        if (from == to) return new Transfer(now, 0, 0);
        if (to.tier() == Tier.DEVICE) throw new IllegalArgumentException("tasks never move back to a device");
        double kb = t.inputKB;
        double time = now;
        double joules = 0;
        boolean fromDevice = from.tier() == Tier.DEVICE;
        if (fromDevice) {
            Link ap = aps[from.zone()];
            time = commit ? ap.reserve(time, kb) : ap.estimate(time, kb);
            joules += DEVICE_TX_W * ap.txMs(kb) / 1000.0;
        }
        if (to.tier() == Tier.FOG && (!fromDevice || to.zone() != from.zone())) {
            time = commit ? lan.reserve(time, kb) : lan.estimate(time, kb);
            joules += kb * 1e-6 * LAN_KWH_PER_GB * 3.6e6;
        }
        if (to.tier() == Tier.CLOUD) {
            time = commit ? wan.reserve(time, kb) : wan.estimate(time, kb);
            time += commit ? Math.abs(rnd.nextGaussian()) * WAN_JITTER_MS : 0.8 * WAN_JITTER_MS;
            joules += kb * 1e-6 * WAN_KWH_PER_GB * 3.6e6;
        }
        double grams = CarbonModel.grams(joules, carbon.gridIntensity(Region.CAMPUS_GRID, now));
        if (commit) {
            energyJ += joules;
            carbonG += grams;
        }
        return new Transfer(time, joules, grams);
    }

    /** Time for the (small) result to travel from the executing node back to the device. */
    public double returnDelay(Task t, ComputeNode n, boolean commit) {
        if (n == t.device) return 0;
        double kb = t.outputKB;
        double d = 0;
        if (n.tier() == Tier.CLOUD) {
            d += WAN_LATENCY_MS + kb * 8 / WAN_MBPS
                    + (commit ? Math.abs(rnd.nextGaussian()) * WAN_JITTER_MS : 0.8 * WAN_JITTER_MS);
        } else if (n.zone() != t.zone) {
            d += LAN_LATENCY_MS + kb * 8 / LAN_MBPS;
        }
        d += AP_LATENCY_MS + kb * 8 / AP_MBPS;
        return d;
    }

    public double wanBacklogMs(double now) { return wan.backlogMs(now); }

    public double energyJ() { return energyJ; }

    public double carbonG() { return carbonG; }

    public double wanGB() { return wan.kbTransferred() / 1e6; }
}
