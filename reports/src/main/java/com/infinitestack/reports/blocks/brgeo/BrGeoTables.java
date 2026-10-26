package com.infinitestack.reports.blocks.brgeo;

import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * The BR Geo tables (published by {@code apps/br-geo-agent-app} in {@code gold}) read by the BR Geo
 * Blocks. Every table is versioned the same way: the current row of a key has {@code valid_to IS NULL}.
 * Bare names go in {@link DataRequirement}s, qualified names in SQL.
 */
public final class BrGeoTables {

    private BrGeoTables() {}

    public static final String SCHEMA = "gold";

    public static final String MUNICIPALITY = "br_geo_agent_app_ibge_municipality";
    public static final String BIOME = "br_geo_agent_app_ibge_biome";
    public static final String EMBARGO = "br_geo_agent_app_ibama_embargo";
    public static final String EMBARGO_DECISION = "br_geo_agent_app_ibama_embargo_decision";
    public static final String DETER_ALERT = "br_geo_agent_app_inpe_deter_alert";
    public static final String PRODES_INCREMENT = "br_geo_agent_app_inpe_prodes_increment";
    public static final String PAM_VALUE = "br_geo_agent_app_ibge_pam_value";
    public static final String PAM_PRODUCT = "br_geo_agent_app_ibge_pam_product";

    public static final String Q_MUNICIPALITY = SCHEMA + "." + MUNICIPALITY;
    public static final String Q_BIOME = SCHEMA + "." + BIOME;
    public static final String Q_EMBARGO = SCHEMA + "." + EMBARGO;
    public static final String Q_EMBARGO_DECISION = SCHEMA + "." + EMBARGO_DECISION;
    public static final String Q_DETER_ALERT = SCHEMA + "." + DETER_ALERT;
    public static final String Q_PRODES_INCREMENT = SCHEMA + "." + PRODES_INCREMENT;
    public static final String Q_PAM_VALUE = SCHEMA + "." + PAM_VALUE;
    public static final String Q_PAM_PRODUCT = SCHEMA + "." + PAM_PRODUCT;

    /** A requirement on one of the BR Geo tables, listing the columns the Block's SQL reads. */
    public static DataRequirement requirement(String table, String... columns) {
        return DataRequirement.table(SCHEMA, table, columns);
    }
}
