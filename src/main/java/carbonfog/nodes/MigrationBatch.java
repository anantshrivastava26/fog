package carbonfog.nodes;

import carbonfog.model.Task;

import java.util.List;

/** Tasks handed back by a node that is being drained. */
public record MigrationBatch(ComputeNode from, List<Task> tasks) {
}
