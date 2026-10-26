package com.infinitestack.reports.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.catalog.CatalogSnapshot;
import com.infinitestack.reports.dto.BlockView;
import com.infinitestack.reports.service.BlockService;
import com.infinitestack.reports.web.CurrentUser;

/**
 * Blocks and their release. Regular users get only the usable Blocks; admins get all of them with
 * the catalog diagnosis, and release or revoke.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api")
public class BlockController {

    private final BlockService service;

    public BlockController(BlockService service) {
        this.service = service;
    }

    @GetMapping("/blocks")
    public List<BlockView> list(CurrentUser user) {
        return service.list(user);
    }

    @GetMapping("/blocks/{id}")
    public BlockView get(@PathVariable String id, CurrentUser user) {
        return service.get(id, user);
    }

    @PostMapping("/blocks/{id}/release")
    public BlockView release(@PathVariable String id, CurrentUser user) {
        return service.release(id, user);
    }

    @DeleteMapping("/blocks/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, CurrentUser user) {
        service.revoke(id, user);
    }

    /** Reads the catalog again now, instead of waiting for the cache to expire. Admin only. */
    @PostMapping("/catalog/refresh")
    public CatalogSnapshot refreshCatalog(CurrentUser user) {
        return service.refreshCatalog(user);
    }
}
