package com.infinitestack.reports.engine.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.ExecutionMetadata;

import jakarta.annotation.PreDestroy;

/**
 * Runs a {@link ValidatedFlow} node by node, in order, synchronously (ARCHITECTURE section 30).
 * Each Block runs on a worker thread so that its timeout can be enforced; the whole execution has
 * its own deadline too, so a Flow of slow Blocks cannot add up past it. The first failure stops
 * the Flow: later nodes would read values that do not exist. An optional node is the exception
 * ({@link FlowNode#optional()}): skipped when a required input is empty, and when it fails the
 * failure is recorded and the Flow goes on with null outputs for it.
 */
@Component
public class FlowExecutor {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutor.class);

    private final ReportsLimits limits;
    private final ExecutorService workers;

    public FlowExecutor(ReportsLimits limits) {
        this.limits = limits;
        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "reports-block-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    public FlowRun run(ValidatedFlow flow, Map<String, Object> inputs, ExecutionMetadata metadata) {
        ExecutionContext context = new ExecutionContext(inputs);
        List<BlockRun> runs = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(limits.executionTimeoutSeconds());

        int position = 0;
        for (ValidatedFlow.Step step : flow.steps()) {
            BlockDefinition definition = step.block().definition();
            String nodeId = step.node().id();
            Instant startedAt = Instant.now();

            Map<String, Object> blockInputs = new LinkedHashMap<>();
            String problem = null;
            String missing = null;
            for (BlockPort port : definition.inputs()) {
                Binding binding = step.bindings().get(port.name());
                Object value = binding == null ? null : context.read(binding);
                if (value == null) {
                    if (port.required()) {
                        missing = port.name();
                        problem = "required input '" + port.name() + "' has no value";
                        break;
                    }
                    continue;
                }
                try {
                    blockInputs.put(port.name(), port.type().coerce(value));
                } catch (IllegalArgumentException e) {
                    problem = "input '" + port.name() + "': " + e.getMessage();
                    break;
                }
            }

            if (problem != null && missing != null && step.node().optional()) {
                skip(context, runs, definition, nodeId, position, BlockRun.Status.SKIPPED, blockInputs, problem, startedAt, metadata);
                position++;
                continue;
            }

            Map<String, Object> outputs = null;
            BlockRun.Status status = BlockRun.Status.COMPLETED;
            Duration timeout = blockTimeout(definition, deadline);
            if (problem == null && (timeout.isNegative() || timeout.isZero())) {
                status = BlockRun.Status.TIMED_OUT;
                problem = "the execution reached its " + limits.executionTimeoutSeconds() + "s limit";
            }
            if (problem == null) {
                Future<Map<String, Object>> future = workers.submit(() -> step.block().execute(blockInputs, metadata));
                try {
                    outputs = checkOutputs(definition, future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    status = BlockRun.Status.TIMED_OUT;
                    problem = "timed out after " + timeout.toMillis() + "ms";
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    problem = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                } catch (InterruptedException e) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    problem = "interrupted";
                } catch (IllegalArgumentException e) {
                    problem = e.getMessage();
                }
            }

            Instant finishedAt = Instant.now();
            long durationMs = Duration.between(startedAt, finishedAt).toMillis();
            if (problem != null && step.node().optional()) {
                // An optional node that fails does not take the report down: the template shows its empty branch.
                skip(context, runs, definition, nodeId, position,
                        status == BlockRun.Status.COMPLETED ? BlockRun.Status.FAILED : status, blockInputs, problem, startedAt, metadata);
                position++;
                continue;
            }
            if (problem != null) {
                if (status == BlockRun.Status.COMPLETED) status = BlockRun.Status.FAILED;
                runs.add(new BlockRun(position, nodeId, definition.id(), definition.version(), status,
                        blockInputs, Map.of(), problem, startedAt, finishedAt, durationMs));
                log.info("[REPORTS] report={} version={} execution={} node={} block={} status={} duration={}ms error={}",
                        metadata.reportId(), metadata.reportVersion(), metadata.executionId(), nodeId,
                        definition.id(), status, durationMs, problem);
                return new FlowRun(context, runs, new FlowRun.Failure(nodeId, definition.id(), problem));
            }
            context.put(nodeId, outputs);
            runs.add(new BlockRun(position, nodeId, definition.id(), definition.version(), status,
                    blockInputs, outputs, null, startedAt, finishedAt, durationMs));
            log.info("[REPORTS] report={} version={} execution={} node={} block={} status=completed duration={}ms",
                    metadata.reportId(), metadata.reportVersion(), metadata.executionId(), nodeId, definition.id(), durationMs);
            position++;
        }
        return new FlowRun(context, runs, null);
    }

    /** Records an optional node that did not produce outputs and gives it null outputs, so the Flow goes on. */
    private static void skip(ExecutionContext context, List<BlockRun> runs, BlockDefinition definition, String nodeId, int position,
                             BlockRun.Status status, Map<String, Object> blockInputs, String problem, Instant startedAt,
                             ExecutionMetadata metadata) {
        Map<String, Object> nulls = new LinkedHashMap<>();
        definition.outputs().forEach(port -> nulls.put(port.name(), null));
        context.put(nodeId, nulls);
        Instant finishedAt = Instant.now();
        String error = status == BlockRun.Status.SKIPPED ? "skipped: " + problem : problem + " (optional node: the report went on without it)";
        runs.add(new BlockRun(position, nodeId, definition.id(), definition.version(), status,
                blockInputs, nulls, error, startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis()));
        log.info("[REPORTS] report={} version={} execution={} node={} block={} status={} optional=true reason={}",
                metadata.reportId(), metadata.reportVersion(), metadata.executionId(), nodeId, definition.id(), status, problem);
    }

    private Duration blockTimeout(BlockDefinition definition, Instant deadline) {
        Duration own = definition.timeout() != null ? definition.timeout() : Duration.ofSeconds(limits.blockTimeoutSeconds());
        Duration left = Duration.between(Instant.now(), deadline);
        return own.compareTo(left) < 0 ? own : left;
    }

    /** Keeps only declared outputs, checks their types and that required ones are present. */
    private static Map<String, Object> checkOutputs(BlockDefinition definition, Map<String, Object> produced) {
        Map<String, Object> source = produced == null ? Map.of() : produced;
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (BlockPort port : definition.outputs()) {
            Object value = source.get(port.name());
            if (value == null) {
                if (port.required()) throw new IllegalArgumentException("block did not produce required output '" + port.name() + "'");
                outputs.put(port.name(), null);
                continue;
            }
            try {
                outputs.put(port.name(), port.type().coerce(value));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("output '" + port.name() + "': " + e.getMessage());
            }
        }
        return outputs;
    }
}
