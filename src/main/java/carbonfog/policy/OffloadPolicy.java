package carbonfog.policy;

import carbonfog.model.Task;
import carbonfog.nodes.ComputeNode;

/** A task-offloading strategy: picks the node that runs each task. */
public interface OffloadPolicy {

    String name();

    /** Choose a node among {@code ctx.candidates()}. */
    ComputeNode decide(Task task, DecisionContext ctx);

    /** Feedback when a task's result reached the device. {@code next} describes the system afterwards. */
    default void onCompleted(Task task, double reward, DecisionContext next) { }

    /** Feedback when the node running the task crashed and the task was lost. */
    default void onLost(Task task, double penalty) { }
}
