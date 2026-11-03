package com.infinitestack.reports.blocks.isgeosam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.infinitestack.reports.catalog.CatalogSnapshot;
import com.infinitestack.reports.catalog.CatalogTable;

class IsGeoSamSchemaSetupTest {

    @TempDir
    Path etc;

    /** The catalog: empty until the schemaOnly run "registers" the tables. */
    List<CatalogTable> tables = new ArrayList<>();

    CatalogSnapshot snapshot() {
        return new CatalogSnapshot("test", Instant.now(), List.copyOf(tables), null);
    }

    static List<CatalogTable> isGeoSamTables() {
        return GoldSceneResults.REQUIREMENTS.stream()
                .map(r -> new CatalogTable("agente-is:reports", "reports", "agent", true, r.schema(), r.table(), "ok", null, List.of()))
                .toList();
    }

    IsGeoSamSchemaSetup setup(IsGeoSamFieldCandidatesBlockTest.FakeRuns runs) {
        IsGeoSamSettings settings = new IsGeoSamSettings("reports", "is-geo-sam", "", etc.resolve("reports-workflows.json"), etc);
        return new IsGeoSamSchemaSetup(runs, this::snapshot, this::snapshot, settings, Duration.ofMillis(1));
    }

    void grant() throws Exception {
        Files.writeString(etc.resolve("reports-workflows.json"), "{\"workflows\":[{\"projectId\":\"p-sam\",\"workflow\":\"is-geo-sam\"}]}");
    }

    @Test
    void tablesAlreadyInTheCatalogNeedNothing() throws Exception {
        grant();
        tables.addAll(isGeoSamTables());
        IsGeoSamFieldCandidatesBlockTest.FakeRuns runs = new IsGeoSamFieldCandidatesBlockTest.FakeRuns();
        assertEquals(IsGeoSamSchemaSetup.State.READY, setup(runs).check());
        assertEquals(0, runs.starts);
    }

    @Test
    void withoutTheGrantItWaitsForIt() throws Exception {
        IsGeoSamFieldCandidatesBlockTest.FakeRuns runs = new IsGeoSamFieldCandidatesBlockTest.FakeRuns();
        IsGeoSamSchemaSetup setup = setup(runs);
        assertEquals(IsGeoSamSchemaSetup.State.NOT_GRANTED, setup.check());
        assertEquals(0, runs.starts);

        grant();                                   // the admin grants it later: the next check asks
        runs.onDone = () -> tables.addAll(isGeoSamTables());
        assertEquals(IsGeoSamSchemaSetup.State.READY, setup.check());
        assertEquals(1, runs.starts);
        assertEquals("p-sam", runs.startedProject);
        assertEquals(Map.of("schemaOnly", true, "appId", "reports"), runs.startedParams);
        assertEquals(null, runs.startedFor);
    }

    @Test
    void itGivesUpAfterAFewRunsThatDidNotRegisterTheTables() throws Exception {
        grant();
        IsGeoSamFieldCandidatesBlockTest.FakeRuns runs = new IsGeoSamFieldCandidatesBlockTest.FakeRuns();
        runs.result = "FAILED";
        IsGeoSamSchemaSetup setup = setup(runs);
        for (int i = 0; i < IsGeoSamSchemaSetup.MAX_RUNS; i++) assertEquals(IsGeoSamSchemaSetup.State.REQUESTED, setup.check());
        assertEquals(IsGeoSamSchemaSetup.State.GAVE_UP, setup.check());
        assertEquals(IsGeoSamSchemaSetup.MAX_RUNS, runs.starts);
    }
}
