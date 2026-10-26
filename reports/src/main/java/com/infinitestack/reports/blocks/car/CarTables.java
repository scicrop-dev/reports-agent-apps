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

    /** A requirement on one of the CAR tables, listing the columns the Block's SQL reads. */
    public static DataRequirement requirement(String table, String... columns) {
        return DataRequirement.table(SCHEMA, table, columns);
    }
}
