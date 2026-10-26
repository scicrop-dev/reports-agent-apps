package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-property}: the registration data of a CAR property, from its current version
 * ({@code valido_ate IS NULL}) with the labels of status, type and condition (BLOCKS.md section 2).
 */
@Component
public class CarPropertyBlock implements ReportBlock {

    static final String SQL = "SELECT i.cod_imovel, i.uf, i.cod_municipio, i.removido_em, m.nome AS municipio,"
            + " v.ind_status, s.descricao AS status_label, v.ind_tipo, t.descricao AS tipo_label,"
            + " c.descricao AS condicao, v.mod_fiscal, v.num_area, v.dat_criacao, v.dat_atualizacao, v.valido_de"
            + " FROM " + Q_IMOVEL + " i"
            + " JOIN " + Q_IMOVEL_VERSAO + " v ON v.imovel_id = i.id AND v.valido_ate IS NULL"
            + " LEFT JOIN " + Q_MUNICIPIO + " m ON m.cod_ibge = i.cod_municipio"
            + " LEFT JOIN " + Q_STATUS_CADASTRO + " s ON s.codigo = v.ind_status"
            + " LEFT JOIN " + Q_TIPO_IMOVEL + " t ON t.codigo = v.ind_tipo"
            + " LEFT JOIN " + Q_CONDICAO + " c ON c.id = v.condicao_id"
            + " WHERE i.cod_imovel = ?";

    private final BlockQueries queries;

    public CarPropertyBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-property", "CAR property",
                "Registration data of a CAR property (current SICAR version)", "1.0.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("codImovel", DataType.STRING, "SICAR property code"),
                        BlockPort.required("uf", DataType.STRING, "state (UF)"),
                        BlockPort.optional("municipality", DataType.STRING, "municipality name"),
                        BlockPort.required("municipalityIbge", DataType.NUMBER, "IBGE municipality code"),
                        BlockPort.required("status", DataType.STRING, "registration status code (AT, PE, SU, CA)"),
                        BlockPort.optional("statusLabel", DataType.STRING, "registration status label"),
                        BlockPort.required("type", DataType.STRING, "property type code (IRU, AST, PCT)"),
                        BlockPort.optional("typeLabel", DataType.STRING, "property type label"),
                        BlockPort.optional("condition", DataType.STRING, "analysis condition"),
                        BlockPort.optional("fiscalModules", DataType.NUMBER, "fiscal modules"),
                        BlockPort.optional("declaredAreaHa", DataType.NUMBER, "declared area, hectares"),
                        BlockPort.optional("registeredAt", DataType.DATE, "registration date"),
                        BlockPort.optional("updatedAt", DataType.DATE, "last update in SICAR"),
                        BlockPort.required("versionSince", DataType.DATE, "date the current version became valid"),
                        BlockPort.optional("removedAt", DataType.DATE, "date the property left SICAR, null if still there")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel", "uf", "cod_municipio", "removido_em"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_ate", "valido_de", "ind_status", "ind_tipo",
                                "condicao_id", "mod_fiscal", "num_area", "dat_criacao", "dat_atualizacao"),
                        requirement(MUNICIPIO, "cod_ibge", "nome"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao"),
                        requirement(TIPO_IMOVEL, "codigo", "descricao"),
                        requirement(CONDICAO, "id", "descricao")),
                null);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        List<Map<String, Object>> rows = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(SQL, (rs, n) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("codImovel", rs.getString("cod_imovel"));
            out.put("uf", rs.getString("uf"));
            out.put("municipality", rs.getString("municipio"));
            out.put("municipalityIbge", rs.getInt("cod_municipio"));
            out.put("status", rs.getString("ind_status"));
            out.put("statusLabel", rs.getString("status_label"));
            out.put("type", rs.getString("ind_tipo"));
            out.put("typeLabel", rs.getString("tipo_label"));
            out.put("condition", rs.getString("condicao"));
            out.put("fiscalModules", CarValues.hectares(rs.getBigDecimal("mod_fiscal")));
            out.put("declaredAreaHa", CarValues.hectares(rs.getBigDecimal("num_area")));
            out.put("registeredAt", CarValues.isoDate(rs, "dat_criacao"));
            out.put("updatedAt", CarValues.isoDate(rs, "dat_atualizacao"));
            out.put("versionSince", CarValues.isoDate(rs, "valido_de"));
            out.put("removedAt", CarValues.isoDate(rs, "removido_em"));
            return out;
        }, codImovel));
        if (rows.isEmpty()) throw CarValues.notFound(codImovel);
        return rows.get(0);
    }
}
