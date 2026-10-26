package com.infinitestack.reports.catalog;

import java.util.List;

/** Where the catalog comes from: the host (production) or the database itself (local development). */
public interface CatalogClient {

    List<CatalogTable> fetchTables() throws Exception;

    /** Shown in the admin screen so it is clear which catalog the diagnosis is based on. */
    String origin();
}
