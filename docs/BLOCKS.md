# REPORTS — Blocks

Catálogo dos Blocks de produto (Fase 8 do [ROADMAP](ROADMAP.md)) e as regras para escrever um.
Dataset Blocks (consulta livre sobre uma base liberada) e prazo de atualização ficam para depois e
estão desenhados em [BLOCKS-FUTURO.md](BLOCKS-FUTURO.md).

## 1. Regras para escrever um Block

- Um Block é um bean Spring que implementa `ReportBlock` (`engine.block`). Ele declara contrato,
  versão e `DataRequirement`s. O `BlockRegistry` o descobre sozinho.
- **Dados só via `BlockQueries.read(timeout, jdbc -> ...)`**: pool somente leitura
  (`blockDataSource`), transação read-only e `statement_timeout`. Um Block nunca recebe o datasource
  principal do app.
- Todo SQL é qualificado (`gold.<tabela>`), com parâmetros `?`. Nada de concatenar valores.
- Os `DataRequirement`s listam **todas** as tabelas e colunas que o SQL lê. É isso que o gate do
  catálogo verifica antes de liberar e antes de cada execução.
- Os outputs são JSON simples (String, Number, Boolean, Map, List). Geometria sai como GeoJSON
  (`ST_AsGeoJSON(geom, 6)`), em SIRGAS 2000 (SRID 4674, o das bases).
- **Áreas em hectares** com `geography` (`ST_Area(geom::geography) / 1e4`) quando calculadas; as
  declaradas no SICAR (`num_area`) são usadas como estão e nomeadas `declared...`.
- Listas têm limite e ordem definidos; o Block avisa (`truncated`) quando cortou.
- "Não encontrado" é falha do Block com mensagem clara (o executor registra o nó e o motivo).
- Mudou o contrato ou o resultado de um Block → nova `version` (semver). As execuções guardam a
  versão que rodou.
- Ids em kebab-case com prefixo da fonte (`car-...`, `brgeo-...`). Nomes e descrições em inglês
  (regra de idioma do AGENTS.md); o texto do relatório fica no template.

## 2. Blocks do CAR (fonte: `apps/car-agent-app`, tabelas `gold.car_agent_app_*`)

Todos recebem `codImovel` (string, obrigatório): o código do imóvel no SICAR
(`UF-<IBGE>-<32 hex>`). Leem a **versão vigente** (`imovel_versao.valido_ate IS NULL`) e as
**feições vigentes** (`feicao.valido_ate IS NULL`).

### `car-property` — Imóvel CAR

| Output | Tipo | Origem |
|---|---|---|
| `codImovel` | string | `imovel.cod_imovel` |
| `uf` | string | `imovel.uf` |
| `municipality` | string | `municipio.nome` (por `imovel.cod_municipio`) |
| `municipalityIbge` | number | `imovel.cod_municipio` |
| `status` / `statusLabel` | string | `imovel_versao.ind_status` / `status_cadastro.descricao` |
| `type` / `typeLabel` | string | `imovel_versao.ind_tipo` / `tipo_imovel.descricao` |
| `condition` | string | `condicao.descricao` (por `imovel_versao.condicao_id`) |
| `fiscalModules` | number | `imovel_versao.mod_fiscal` |
| `declaredAreaHa` | number | `imovel_versao.num_area` |
| `registeredAt` | date | `imovel_versao.dat_criacao` |
| `updatedAt` | date | `imovel_versao.dat_atualizacao` |
| `versionSince` | date | `imovel_versao.valido_de` |
| `removedAt` | date, opcional | `imovel.removido_em` (null se o imóvel segue no SICAR) |

### `car-property-geometry` — Geometria do imóvel

| Output | Tipo | Origem |
|---|---|---|
| `geometry` | geometry | GeoJSON da geometria vigente (`imovel_geometria.geom` por `imovel_versao.geometria_id`) |
| `areaHa` | number | área calculada (`imovel_geometria.area_calc_ha`) |
| `centroidLat` / `centroidLon` | number | `ST_PointOnSurface` (sempre dentro do polígono) |
| `bbox` | object | `{minLon, minLat, maxLon, maxLat}` |
| `vertices` | number | `ST_NPoints` |

### `car-environmental-layers` — Uso e cobertura declarados

Lê as feições vigentes por camada (`APPS`, `VEGETACAO_NATIVA`, `AREA_CONSOLIDADA`, `AREA_POUSIO`,
`HIDROGRAFIA`, `USO_RESTRITO`, `SERVIDAO_ADMINISTRATIVA`, `RESERVA_LEGAL`; `AREA_IMOVEL` fica de
fora). A área de uma camada é a do seu tema agregado (`tema.agregado = true`, ex.: `APP_TOTAL`)
quando existir; senão, a soma dos temas. Nunca soma o agregado com as partes.

| Output | Tipo | Conteúdo |
|---|---|---|
| `layers` | array | um item por camada presente, na ordem de `camada.ordem`: `{layer, layerName, declaredAreaHa, percentOfProperty, themes: [{theme, themeName, count, declaredAreaHa}]}` |
| `summary` | object | `{propertyAreaHa, appHa, legalReserveHa, nativeVegetationHa, consolidatedHa, fallowHa, hydrographyHa, restrictedUseHa, administrativeEasementHa, appPercent, legalReservePercent, nativeVegetationPercent}`; camada ausente = 0 |
| `hasLegalReserve` | boolean | existe feição vigente de `RESERVA_LEGAL` |

`propertyAreaHa` é a área declarada da versão vigente; percentuais com 2 casas.

### `car-overlaps` — Sobreposição com outros imóveis CAR

| Input | Tipo | |
|---|---|---|
| `codImovel` | string | obrigatório |
| `minOverlapHa` | number | opcional, default 0.01 (ignora encostas de borda) |

| Output | Tipo | Conteúdo |
|---|---|---|
| `overlaps` | array | até 100, maior sobreposição primeiro: `{codImovel, status, statusLabel, overlapHa, percentOfProperty}` |
| `count` | number | quantos imóveis vigentes se sobrepõem (acima do mínimo) |
| `totalOverlapHa` | number | soma das áreas de sobreposição par a par (pode contar a mesma área mais de uma vez) |
| `truncated` | boolean | mais de 100 |

Usa o índice GiST (`&&` + `ST_Intersects`), timeout próprio de 30 s.

### `car-history` — Histórico do cadastro

| Output | Tipo | Conteúdo |
|---|---|---|
| `versions` | array | da mais antiga para a mais nova: `{validFrom, validTo, status, statusLabel, condition, declaredAreaHa, geometryChanged}` |
| `count` | number | número de versões |

`geometryChanged` = a versão aponta para uma geometria diferente da anterior.

### `car-property-map` — Mapa do imóvel

| Input | Tipo | |
|---|---|---|
| `codImovel` | string | obrigatório |
| `width` / `height` | number | opcionais, default 1000 × 700 px |

| Output | Tipo | Conteúdo |
|---|---|---|
| `image` | string | PNG em `data:image/png;base64,...`, pronto para `<img src="{{map.image}}">` |
| `legend` | array | `{layer, layerName, color}` das camadas desenhadas |

Desenha, em projeção equirretangular corrigida pela latitude: o contorno do imóvel, as feições
vigentes por camada (cores fixas por camada, semitransparentes), os imóveis vizinhos que tocam a
caixa do imóvel (só contorno, cinza), seta de norte, barra de escala e as coordenadas dos cantos.
Geometrias simplificadas (`ST_SimplifyPreserveTopology`) na resolução da imagem. Sem rede, sem
tiles: só os dados do banco, então o PDF fica autocontido.

## 2.1 Notas de implementação e dos dados (verificadas no banco de dev em 2026-10-26)

- **Outputs que podem vir nulos são opcionais** no contrato real (`municipality`, rótulos, `condition`,
  `fiscalModules`, `declaredAreaHa`, datas, `removedAt`): o executor recusa nulo em output obrigatório.
  O template imprime vazio para nulo.
- **Hidrografia não tem `num_area` no SICAR** (nulo nas amostras): entra como 0 ha. Pendente decidir
  se cai para a área calculada da geometria, marcada como "calculada".
- **Temas de APP se sobrepõem** (APP em vegetação nativa dentro de APP de rio): por isso a camada usa
  o tema agregado (`APP_TOTAL`) quando existe. Só APPS e SERVIDAO_ADMINISTRATIVA têm agregado.
- `car-overlaps` calcula o percentual sobre a **área calculada** (coerente com a sobreposição, que é
  geodésica); camadas e resumo usam a **área declarada**.
- `car-overlaps` passa as geometrias por `ST_MakeValid`, para um vizinho inválido não derrubar o Block.
- `car-property-map` busca vizinhos na extensão inteira da imagem (+5%), recortados com
  `ST_ClipByBox2D`, os mais próximos primeiro (até 300); simplificação de 1 pixel. PNG de 40 a 65 KB.
  Todo texto do mapa é opcional: se o JRE do host não tiver fontes, o mapa sai sem rótulos.
- Temas dentro de uma camada saem ordenados pelo código.
- No dev, BA não tem feições de RESERVA_LEGAL (as amostras com reserva são do AC) e nenhum imóvel tem
  `removido_em`; nenhuma amostra tem mais de uma versão. `geometryChanged = true` e `removedAt` só
  estão cobertos por teste unitário e pelos dublês.
- Tempos no dev, por imóvel: dados 45 a 70 ms por Block, mapa 170 a 280 ms; laudo inteiro ~0,5 s.

## 3. Blocks previstos (BR Geo, quando o `br-geo-agent-app` estiver no ambiente)

| Block | Entrada → saída | Dados |
|---|---|---|
| `brgeo-municipality-at` | lat/lon → município, UF, código IBGE | `gold.br_geo_agent_app_ibge_municipality` |
| `brgeo-biomes` | geometria → biomas e % da área | `gold.br_geo_agent_app_ibge_biome` |
| `brgeo-embargoes` | geometria → embargos IBAMA que intersectam | `gold.br_geo_agent_app_ibama_embargo` |
| `brgeo-deter-alerts` | geometria + período → alertas DETER e área | `gold.br_geo_agent_app_inpe_deter_alert` |
| `brgeo-prodes` | geometria → incrementos PRODES por ano | `gold.br_geo_agent_app_inpe_prodes_increment` |

Entram no laudo socioambiental assim que os dados estiverem no catálogo: o gate de disponibilidade
já cuida de mostrá-los só quando existirem.
