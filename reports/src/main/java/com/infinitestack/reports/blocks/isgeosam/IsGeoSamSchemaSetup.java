package com.infinitestack.reports.blocks.isgeosam;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;
import com.infinitestack.reports.catalog.CatalogService;
import com.infinitestack.reports.catalog.CatalogSnapshot;
import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * Makes sure the gold tables of is-geo-sam exist and are in the catalog, so that the
 * {@code isgeosam-field-candidates} Block can be released: the catalog gate needs them before the
 * first segmentation could ever create them.
 *
 * <p>is-geo-sam creates and registers them (under this app, through the {@code appId} of the params
 * or the IS_APP_ID the back sets) when asked {@code {"schemaOnly": true}}: no model, no data. This
 * checks the catalog after startup and then every few minutes, and asks for that run while the tables
 * are missing and the workflow is granted. It stops once the catalog has them, or after a few runs
 * that did not get them there (the reason is in the log and in Workloads). The grant can come after
 * the app started: the grants file is read on every check.
 */
@Component
public class IsGeoSamSchemaSetup {

    private static final Logger log = LoggerFactory.getLogger(IsGeoSamSchemaSetup.class);

    static final Duration FIRST_CHECK = Duration.ofSeconds(30);
    static final Duration EVERY = Duration.ofMinutes(5);
    static final Duration RUN_WAIT = Duration.ofMinutes(10);
    static final int MAX_RUNS = 3;
    static final String RESULT_MARKER = "IS_GEO_SAM_RESULT ";

    /** What the setup is doing, for the log and the tests. */
    enum State { NOT_GRANTED, READY, REQUESTED, FAILED, GAVE_UP }

    private final WorkflowRuns runs;
    /** The catalog as cached, and as read again right now. */
    private final Supplier<CatalogSnapshot> catalog;
    private final Supplier<CatalogSnapshot> freshCatalog;
    private final IsGeoSamSettings settings;
    private final Duration pollInterval;
    private int runsAsked;
    private State last;
    private ScheduledExecutorService scheduler;

    @Autowired
    public IsGeoSamSchemaSetup(WorkflowRuns runs, CatalogService catalog, IsGeoSamSettings settings) {
        this(runs, catalog::snapshot, catalog::refresh, settings, Duration.ofSeconds(5));
    }

    IsGeoSamSchemaSetup(WorkflowRuns runs, Supplier<CatalogSnapshot> catalog, Supplier<CatalogSnapshot> freshCatalog,
                        IsGeoSamSettings settings, Duration pollInterval) {
        this.runs = runs;
        this.catalog = catalog;
        this.freshCatalog = freshCatalog;
        this.settings = settings;
        this.pollInterval = pollInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startChecking() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "reports-isgeosam-setup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            State state = check();
            if (state == State.READY || state == State.GAVE_UP) scheduler.shutdown();
        }, FIRST_CHECK.toSeconds(), EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** One round: nothing to do, not granted yet, or one schemaOnly run. Never throws. */
    synchronized State check() {
        try {
            State state = round();
            if (state != last) {
                switch (state) {
                    case NOT_GRANTED -> log.info("[reports] is-geo-sam: {}", settings.missingProjectMessage());
                    case READY -> log.info("[reports] is-geo-sam: gold.is_geo_sam_* tables are in the catalog");
                    case GAVE_UP -> log.warn("[reports] is-geo-sam: {} schemaOnly runs did not put the tables in the catalog; "
                            + "check the runs in Workloads and restart the app to try again", MAX_RUNS);
                    default -> { }
                }
            }
            last = state;
            return state;
        } catch (Exception e) {
            log.warn("[reports] is-geo-sam: could not prepare the tables: {}", e.getMessage());
            last = State.FAILED;
            return State.FAILED;
        }
    }

    private State round() throws Exception {
        if (tablesInCatalog(catalog.get())) return State.READY;
        Optional<String> project = settings.projectId();
        if (project.isEmpty()) return State.NOT_GRANTED;
        if (runsAsked >= MAX_RUNS) return State.GAVE_UP;

        runsAsked++;
        String triggerId = runs.start(project.get(), settings.workflow(), settings.params(Map.of("schemaOnly", true)), null);
        log.info("[reports] is-geo-sam: tables missing from the catalog; asked project {} to create them (run {})",
                project.get(), triggerId);
        WorkflowRuns.RunState state = waitUntilDone(project.get(), triggerId);
        String line = resultLine(project.get(), triggerId);
        log.info("[reports] is-geo-sam: schemaOnly run {} ended {}{}", triggerId, state == null ? "(still running)" : state.result(),
                line == null ? "" : ": " + line);
        return tablesInCatalog(freshCatalog.get()) ? State.READY : State.REQUESTED;
    }

    static boolean tablesInCatalog(CatalogSnapshot snapshot) {
        Set<String> present = snapshot.tables().stream()
                .map(t -> t.schema() + "." + t.table()).collect(Collectors.toSet());
        List<String> needed = GoldSceneResults.REQUIREMENTS.stream().map(DataRequirement::qualifiedName).toList();
        return present.containsAll(needed);
    }

    /** @return the final state, or null when the run did not end within {@link #RUN_WAIT} */
    private WorkflowRuns.RunState waitUntilDone(String projectId, String triggerId) throws Exception {
        Instant deadline = Instant.now().plus(RUN_WAIT);
        while (true) {
            WorkflowRuns.RunState state = runs.get(projectId, triggerId);
            if (state.done()) return state;
            if (Instant.now().isAfter(deadline)) return null;
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** The script's IS_GEO_SAM_RESULT line, for the log; null when there is none. */
    private String resultLine(String projectId, String triggerId) {
        try {
            String log = runs.output(projectId, triggerId, 0, 262_144).content();
            int at = log.lastIndexOf(RESULT_MARKER);
            return at < 0 ? null : log.substring(at).lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
