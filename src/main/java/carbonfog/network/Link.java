package carbonfog.network;

/**
 * A shared FIFO link (store-and-forward). Transfers are serialised: a new
 * transfer starts when the previous one has left the link.
 */
public final class Link {
    public final String name;
    public final double mbps;
    public final double latencyMs;
    private double busyUntil;
    private double kbTransferred;

    public Link(String name, double mbps, double latencyMs) {
        this.name = name;
        this.mbps = mbps;
        this.latencyMs = latencyMs;
    }

    /** Serialisation time of kb kilobytes in ms (1 Mbps = 1 kbit per ms). */
    public double txMs(double kb) { return kb * 8.0 / mbps; }

    /** Book the link: returns the time the data arrives at the far end. */
    public double reserve(double readyAt, double kb) {
        double start = Math.max(readyAt, busyUntil);
        double end = start + txMs(kb);
        busyUntil = end;
        kbTransferred += kb;
        return end + latencyMs;
    }

    /** Same as reserve() but without booking (for what-if estimates). */
    public double estimate(double readyAt, double kb) {
        return Math.max(readyAt, busyUntil) + txMs(kb) + latencyMs;
    }

    public double backlogMs(double now) { return Math.max(0, busyUntil - now); }

    public double kbTransferred() { return kbTransferred; }
}
