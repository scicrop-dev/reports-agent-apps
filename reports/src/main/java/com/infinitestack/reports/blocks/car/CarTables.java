package com.infinitestack.reports.blocks.car;

import com.infinitestack.reports.engine.block.DataRequirement;

/**
 * The CAR tables (published by {@code apps/car-agent-app} in {@code gold}) read by the CAR Blocks.
 * Each table has its bare name, used in {@link DataRequirement}s, and its qualified name, used in
 * SQL (BLOCKS.md section 1: every SQL is qualified, never dependent on search_path).
 */
public final class CarTables {

    private CarTables() {}

    public static final String SCHEMA = "gold";

    public static final String IMOVEL = "car_agent_app_imovel";
    public static final String IMOVEL_VERSAO = "car_agent_app_imovel_versao";
    public static final String IMOVEL_GEOMETRIA = "car_agent_app_imovel_geometria";
    public static final String FEICAO = "car_agent_app_feicao";
    public static final String MUNICIPIO = "car_agent_app_municipio";
    public static final String CAMADA = "car_agent_app_camada";
    public static final String TEMA = "car_agent_app_tema";
    public static final String STATUS_CADASTRO = "car_agent_app_status_cadastro";
    public static final String TIPO_IMOVEL = "car_agent_app_tipo_imovel";
    public static final String CONDICAO = "car_agent_app_condicao";
    public static final String CARGA = "car_agent_app_carga";
    public static final String DESCARTADO = "car_agent_app_descartado";

    public static final String Q_IMOVEL = SCHEMA + "." + IMOVEL;
    public static final String Q_IMOVEL_VERSAO = SCHEMA + "." + IMOVEL_VERSAO;
    public static final String Q_IMOVEL_GEOMETRIA = SCHEMA + "." + IMOVEL_GEOMETRIA;
    public static final String Q_FEICAO = SCHEMA + "." + FEICAO;
    public static final String Q_MUNICIPIO = SCHEMA + "." + MUNICIPIO;
    public static final String Q_CAMADA = SCHEMA + "." + CAMADA;
    public static final String Q_TEMA = SCHEMA + "." + TEMA;
    public static final String Q_STATUS_CADASTRO = SCHEMA + "." + STATUS_CADASTRO;
    public static final String Q_TIPO_IMOVEL = SCHEMA + "." + TIPO_IMOVEL;
    public static final String Q_CONDICAO = SCHEMA + "." + CONDICAO;
    public static final String Q_CARGA = SCHEMA + "." + CARGA;
    public static final String Q_DESCARTADO = SCHEMA + "." + DESCARTADO;

    /**
     * The version of a property a Block reads, as {@code v}, for an outer query where the property
     * is {@code i}: the current version ({@code valido_ate IS NULL}) or, when the property left the
     * SICAR ({@code imovel.removido_em} set), its last version. The car-agent-app loader closes the
     * current version with {@code valido_ate = removido_em} in the same step that marks the removal,
     * so a removed property has no current version. Uses the unique index (imovel_id, valido_de).
     */
    public static final String VERSION_IN_EFFECT = " JOIN LATERAL (SELECT lv.* FROM " + Q_IMOVEL_VERSAO + " lv"
            + " WHERE lv.imovel_id = i.id AND (lv.valido_ate IS NULL OR i.removido_em IS NOT NULL)"
            + " ORDER BY lv.valido_de DESC LIMIT 1) v ON true";

    /**
     * Condition on a feature {@code f} matching {@link #VERSION_IN_EFFECT} ({@code i}, {@code v} in
     * scope): the current features, or for a removed property the last set of each layer. The
     * loader closes a layer's features when that layer's file no longer lists the property, at that
     * file's publication date, which need not be the date of the removal (or they may stay current
     * if the file still lists it). So the last set per layer counts unless it was closed on or
     * before the start of the last version, which means the layer was already empty then.
     */
    public static final String FEATURE_IN_EFFECT = "((i.removido_em IS NULL AND f.valido_ate IS NULL)"
            + " OR (i.removido_em IS NOT NULL"
            + " AND f.valido_de = (SELECT max(lf.valido_de) FROM " + Q_FEICAO + " lf"
            + " WHERE lf.imovel_id = f.imovel_id AND lf.camada = f.camada)"
            + " AND (f.valido_ate IS NULL OR f.valido_ate > v.valido_de)))";

    /** A requirement on one of the CAR tables, listing the columns the Block's SQL reads. */
    public static DataRequirement requirement(String table, String... columns) {
        return DataRequirement.table(SCHEMA, table, columns);
    }
}
