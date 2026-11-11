package carbonfog.sensors;

import carbonfog.model.Task;
import carbonfog.nodes.ComputeNode;
import carbonfog.sim.OffloadEvents;
import carbonfog.util.Rand;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.Random;

/**
 * A simulated physical sensor attached to an IoT device. Readings arrive as a
 * non-homogeneous Poisson process whose rate follows campus activity; each
 * reading becomes a compute task submitted to the offloading controller.
 */
public final class IoTSensor extends SimEntity {

    private final ComputeNode device;
    private final SensorDataGenerator generator;
    private final Random rnd;
    private final double loadScale;
    private final double stopAtMs;
    private int controllerId = -1;

    public IoTSensor(String name, ComputeNode device, SensorDataGenerator generator,
                     double loadScale, double stopAtMs, long seed) {
        super(name);
        this.device = device;
        this.generator = generator;
        this.rnd = Rand.rng(seed);
        this.loadScale = loadScale;
        this.stopAtMs = stopAtMs;
    }

    public void setControllerId(int id) { this.controllerId = id; }

    @Override
    public void startEntity() {
        scheduleNext();
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == OffloadEvents.EMIT_TASK) {
            emit();
            scheduleNext();
        }
    }

    private void emit() {
        double now = CloudSim.clock();
        SensorDataGenerator.Reading r = generator.next(now);
        Task t = new Task(generator.type(), device, device.zone(), now,
                generator.type().baseMI * r.miFactor(), r.inputKB(), r.payload());
        sendNow(controllerId, OffloadEvents.TASK_SUBMIT, t);
    }

    private void scheduleNext() {
        double now = CloudSim.clock();
        double rate = CampusActivity.level(generator.type(), now) * loadScale;
        double gap = Rand.exponential(rnd, generator.type().meanIntervalMs / Math.max(rate, 1e-3));
        if (now + gap < stopAtMs) send(getId(), gap, OffloadEvents.EMIT_TASK);
    }

    @Override
    public void shutdownEntity() { }
}
