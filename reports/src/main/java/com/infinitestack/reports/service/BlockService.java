package com.infinitestack.reports.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.catalog.AvailabilityStatus;
import com.infinitestack.reports.catalog.BlockAvailability;
import com.infinitestack.reports.catalog.BlockAvailabilityService;
import com.infinitestack.reports.catalog.BlockRelease;
import com.infinitestack.reports.catalog.CatalogService;
import com.infinitestack.reports.catalog.CatalogSnapshot;
import com.infinitestack.reports.dto.BlockView;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.repository.BlockReleaseRepository;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/**
 * The gate between the catalog and the people building reports (ROADMAP, Phase 3). A Block is
 * usable when its data is AVAILABLE in the catalog <b>and</b> an admin released it. Regular users
 * only see usable Blocks; admins see every Block with the diagnosis, and release or revoke them.
 */
@Service
public class BlockService {

    private static final Logger log = LoggerFactory.getLogger(BlockService.class);

    private final BlockRegistry registry;
    private final CatalogService catalog;
    private final BlockAvailabilityService availability;
    private final BlockReleaseRepository releases;
    private final ObjectMapper mapper;

    public BlockService(BlockRegistry registry, CatalogService catalog, BlockAvailabilityService availability,
                        BlockReleaseRepository releases, ObjectMapper mapper) {
        this.registry = registry;
        this.catalog = catalog;
        this.availability = availability;
        this.releases = releases;
        this.mapper = mapper;
    }

    public List<BlockView> list(CurrentUser user) {
        CatalogSnapshot snapshot = catalog.snapshot();
        Map<String, BlockRelease> released = releases.findAll();
        List<BlockView> out = new ArrayList<>();
        for (ReportBlock block : registry.all()) {
            BlockView view = view(block, snapshot, released.get(block.definition().id()));
            if (user.isAdmin() || view.usable()) out.add(view);
        }
        return out;
    }

    public BlockView get(String blockId, CurrentUser user) {
        ReportBlock block = registry.find(blockId).orElseThrow(() -> ApiException.notFound("Unknown block: " + blockId));
        BlockView view = view(block, catalog.snapshot(), releases.find(blockId).orElse(null));
        if (!user.isAdmin() && !view.usable()) throw ApiException.notFound("Unknown block: " + blockId);
        return view;
    }

    public BlockView release(String blockId, CurrentUser user) {
        requireAdmin(user);
        ReportBlock block = registry.find(blockId).orElseThrow(() -> ApiException.notFound("Unknown block: " + blockId));
        // Decide on fresh data: releasing on a cached "available" that has since disappeared would be wrong.
        CatalogSnapshot snapshot = catalog.refresh();
        BlockAvailability diagnosis = availability.check(block.definition(), snapshot);
        if (diagnosis.status() != AvailabilityStatus.AVAILABLE) {
            throw ApiException.conflict("Block " + blockId + " cannot be released: its data is " + diagnosis.status(),
                    diagnosis.problems());
        }
        BlockRelease release = new BlockRelease(blockId, block.definition().version(), user.id(), user.username(),
                Instant.now(), mapper.valueToTree(diagnosis));
        releases.save(release);
        log.info("[reports] block {} {} released by {}", blockId, block.definition().version(), user.id());
        return new BlockView(block.definition(), diagnosis, release, true);
    }

    public void revoke(String blockId, CurrentUser user) {
        requireAdmin(user);
        registry.find(blockId).orElseThrow(() -> ApiException.notFound("Unknown block: " + blockId));
        releases.delete(blockId);
        log.info("[reports] block {} revoked by {}", blockId, user.id());
    }

    public CatalogSnapshot refreshCatalog(CurrentUser user) {
        requireAdmin(user);
        return catalog.refresh();
    }

    /**
     * Re-checks, right before an execution, that every Block it uses is still usable: data can
     * disappear from the catalog after a release. Returns the problems, empty when all is well.
     */
    public List<String> problemsFor(Collection<String> blockIds) {
        CatalogSnapshot snapshot = catalog.snapshot();
        Map<String, BlockRelease> released = releases.findAll();
        List<String> problems = new ArrayList<>();
        for (String blockId : blockIds.stream().distinct().toList()) {
            ReportBlock block = registry.find(blockId).orElse(null);
            if (block == null) {
                problems.add("block " + blockId + " does not exist");
                continue;
            }
            if (!released.containsKey(blockId)) {
                problems.add("block " + blockId + " is not released");
                continue;
            }
            BlockAvailability diagnosis = availability.check(block.definition(), snapshot);
            if (diagnosis.status() != AvailabilityStatus.AVAILABLE) {
                problems.add("block " + blockId + " has no usable data (" + diagnosis.status() + "): "
                        + String.join("; ", diagnosis.problems()));
            }
        }
        return problems;
    }

    private BlockView view(ReportBlock block, CatalogSnapshot snapshot, BlockRelease release) {
        BlockAvailability diagnosis = availability.check(block.definition(), snapshot);
        boolean usable = release != null && diagnosis.status() == AvailabilityStatus.AVAILABLE;
        return new BlockView(block.definition(), diagnosis, release, usable);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw ApiException.forbidden("Only ADMIN or SCICROP can release blocks");
    }
}
