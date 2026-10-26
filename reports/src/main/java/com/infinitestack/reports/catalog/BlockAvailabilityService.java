package com.infinitestack.reports.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * Compares what a Block declares it needs with what the catalog holds: the table under an active
 * source, the columns, and how recent the data is when the Block sets a limit. A Block with no
 * requirement (pure computation) is always AVAILABLE and depends only on being released.
 */
@Service
public class BlockAvailabilityService {

    private final Clock clock;

    public BlockAvailabilityService() {
        this(Clock.systemUTC());
    }

    BlockAvailabilityService(Clock clock) {
        this.clock = clock;
    }

    public BlockAvailability check(BlockDefinition definition, CatalogSnapshot catalog) {
        List<RequirementCheck> checks = new ArrayList<>();
        for (DataRequirement requirement : definition.dataRequirements()) {
            checks.add(check(requirement, catalog));
        }
        AvailabilityStatus status = AvailabilityStatus.AVAILABLE;
        for (RequirementCheck c : checks) {
            if (c.status() == AvailabilityStatus.MISSING_DATA) {
                status = AvailabilityStatus.MISSING_DATA;
                break;
            }
            if (c.status() == AvailabilityStatus.STALE) status = AvailabilityStatus.STALE;
        }
        return new BlockAvailability(definition.id(), status, catalog.origin(), catalog.error(), checks);
    }

    RequirementCheck check(DataRequirement requirement, CatalogSnapshot catalog) {
        List<CatalogTable> matches = catalog.tables().stream()
                .filter(t -> requirement.schema().equalsIgnoreCase(t.schema()) && requirement.table().equalsIgnoreCase(t.table()))
                .toList();
        List<CatalogTable> active = matches.stream().filter(CatalogTable::sourceActive).toList();
        if (active.isEmpty()) {
            String problem = matches.isEmpty()
                    ? "table " + requirement.qualifiedName() + " is not in the catalog"
                    : "table " + requirement.qualifiedName() + " is only under inactive sources ("
                            + matches.stream().map(CatalogTable::sourceId).collect(Collectors.joining(", ")) + ")";
            return new RequirementCheck(requirement, AvailabilityStatus.MISSING_DATA, null, null, List.of(problem));
        }

        // Several active sources may list the table; the most recently updated one is the best evidence.
        CatalogTable table = active.stream()
                .max(Comparator.comparing((CatalogTable t) -> parse(t.lastUpdate()), Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();

        Set<String> present = table.columns().stream()
                .map(c -> c.name() == null ? "" : c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> missing = requirement.columns().stream()
                .filter(c -> !present.contains(c.toLowerCase(Locale.ROOT))).toList();
        if (!missing.isEmpty()) {
            return new RequirementCheck(requirement, AvailabilityStatus.MISSING_DATA, table.sourceId(), table.lastUpdate(),
                    List.of("table " + requirement.qualifiedName() + " has no column(s) " + String.join(", ", missing)));
        }

        Duration maxAge = requirement.maxStaleness();
        if (maxAge != null) {
            Instant updated = parse(table.lastUpdate());
            if (updated == null) {
                return new RequirementCheck(requirement, AvailabilityStatus.STALE, table.sourceId(), table.lastUpdate(),
                        List.of("table " + requirement.qualifiedName() + " has no known last update; the block needs data newer than " + maxAge));
            }
            if (updated.plus(maxAge).isBefore(clock.instant())) {
                return new RequirementCheck(requirement, AvailabilityStatus.STALE, table.sourceId(), table.lastUpdate(),
                        List.of("table " + requirement.qualifiedName() + " was last updated at " + table.lastUpdate()
                                + ", older than the " + maxAge + " the block accepts"));
            }
        }
        return new RequirementCheck(requirement, AvailabilityStatus.AVAILABLE, table.sourceId(), table.lastUpdate(), List.of());
    }

    /** The catalog stores last_update as text in a few ISO-8601 shapes; anything else is unknown. */
    static Instant parse(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim();
        try { return Instant.parse(value); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        try { return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        return null;
    }
}
