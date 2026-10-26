package com.infinitestack.reports.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.support.TestBlocks;

class BlockAvailabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final BlockAvailabilityService service = new BlockAvailabilityService(Clock.fixed(NOW, ZoneOffset.UTC));
    private final DataRequirement requirement = TestBlocks.NeedsTable.REQUIREMENT;

    private static CatalogTable table(String source, boolean active, String lastUpdate, String... columns) {
        return new CatalogTable(source, source, "agente-is", active, "gold", "reports_test_area", "GREEN", lastUpdate,
                java.util.Arrays.stream(columns).map(c -> new CatalogColumn(c, "text", null, null)).toList());
    }

    private static CatalogSnapshot catalog(CatalogTable... tables) {
        return new CatalogSnapshot("host", NOW, List.of(tables), null);
    }

    @Test
    void missingTable() {
        RequirementCheck check = service.check(requirement, catalog());
        assertEquals(AvailabilityStatus.MISSING_DATA, check.status());
        assertTrue(check.problems().get(0).contains("not in the catalog"));
    }

    @Test
    void tableOnlyUnderAnInactiveSource() {
        RequirementCheck check = service.check(requirement, catalog(table("agente-is:old-app", false, null, "code", "name")));
        assertEquals(AvailabilityStatus.MISSING_DATA, check.status());
        assertTrue(check.problems().get(0).contains("inactive"), check.problems().toString());
    }

    @Test
    void missingColumnIsNamedAndCaseIsIgnored() {
        RequirementCheck check = service.check(requirement, catalog(table("s", true, null, "CODE")));
        assertEquals(AvailabilityStatus.MISSING_DATA, check.status());
        assertTrue(check.problems().get(0).endsWith("name"), check.problems().toString());
    }

    @Test
    void availableWhenEverythingIsThere() {
        BlockAvailability availability = service.check(new TestBlocks.NeedsTable().definition(),
                catalog(table("s", true, "2026-10-20T00:00:00Z", "code", "name", "extra")));
        assertEquals(AvailabilityStatus.AVAILABLE, availability.status());
        assertTrue(availability.problems().isEmpty());
    }

    @Test
    void staleWhenOlderThanTheBlockAccepts() {
        DataRequirement fresh = requirement.freshWithin(Duration.ofDays(3));
        assertEquals(AvailabilityStatus.STALE,
                service.check(fresh, catalog(table("s", true, "2026-10-20 08:00:00", "code", "name"))).status());
        assertEquals(AvailabilityStatus.STALE,
                service.check(fresh, catalog(table("s", true, null, "code", "name"))).status());
        assertEquals(AvailabilityStatus.AVAILABLE,
                service.check(fresh, catalog(table("s", true, "2026-10-25", "code", "name"))).status());
    }

    @Test
    void theFreshestActiveSourceWins() {
        DataRequirement fresh = requirement.freshWithin(Duration.ofDays(3));
        RequirementCheck check = service.check(fresh, catalog(
                table("old", true, "2026-01-01T00:00:00Z", "code", "name"),
                table("new", true, "2026-10-26T00:00:00Z", "code", "name")));
        assertEquals(AvailabilityStatus.AVAILABLE, check.status());
        assertEquals("new", check.sourceId());
    }

    @Test
    void aBlockWithoutRequirementsIsAlwaysAvailable() {
        assertEquals(AvailabilityStatus.AVAILABLE,
                service.check(new TestBlocks.Upper().definition(), catalog()).status());
    }
}
