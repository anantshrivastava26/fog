package carbonfog.failure;

import carbonfog.nodes.ComputeNode;
import carbonfog.sim.OffloadEvents;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.List;

/** Schedules the degradation, crash, recovery and anomaly events of a {@link FailurePlan}. */
public final class FailureInjector extends SimEntity {

    private final FailurePlan plan;
    private final List<ComputeNode> fogs;

    public FailureInjector(FailurePlan plan, List<ComputeNode> fogs) {
        super("failure-injector");
        this.plan = plan;
        this.fogs = fogs;
    }

    @Override
    public void startEntity() {
        for (FailurePlan.Crash c : plan.crashes) {
            int id = fogs.get(c.fogIndex()).getId();
            if (c.predictable()) send(id, c.degradeStart(), OffloadEvents.DEGRADE_START, c.failAt());
            send(id, c.failAt(), OffloadEvents.NODE_FAIL);
            send(id, c.recoverAt(), OffloadEvents.NODE_RECOVER);
        }
        for (FailurePlan.Spike s : plan.spikes) {
            send(fogs.get(s.fogIndex()).getId(), s.start(), OffloadEvents.SPIKE_START, s);
        }
    }

    @Override
    public void processEvent(SimEvent ev) { }

    @Override
    public void shutdownEntity() { }
}
