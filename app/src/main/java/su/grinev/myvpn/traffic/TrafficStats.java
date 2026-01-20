package su.grinev.myvpn.traffic;

/**
 * Immutable snapshot of traffic statistics.
 */
public class TrafficStats {
    private final long incomingBytes;
    private final long outgoingBytes;
    private final double incomingRateMbps;
    private final double outgoingRateMbps;
    private final long timestamp;

    public TrafficStats(long incomingBytes, long outgoingBytes,
                        double incomingRateMbps, double outgoingRateMbps) {
        this.incomingBytes = incomingBytes;
        this.outgoingBytes = outgoingBytes;
        this.incomingRateMbps = incomingRateMbps;
        this.outgoingRateMbps = outgoingRateMbps;
        this.timestamp = System.currentTimeMillis();
    }

    public long getIncomingBytes() {
        return incomingBytes;
    }

    public long getOutgoingBytes() {
        return outgoingBytes;
    }

    public double getIncomingRateMbps() {
        return incomingRateMbps;
    }

    public double getOutgoingRateMbps() {
        return outgoingRateMbps;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getIncomingGigabytes() {
        return incomingBytes / (1024.0 * 1024.0 * 1024.0);
    }

    public double getOutgoingGigabytes() {
        return outgoingBytes / (1024.0 * 1024.0 * 1024.0);
    }
}
