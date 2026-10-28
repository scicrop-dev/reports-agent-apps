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
- Ids em kebab-case com prefixo da fonte (`car-...`, `brgeo-...`, `sathub-...`). Nomes e descrições
  em inglês (regra de idioma do AGENTS.md); o texto do relatório fica no template.
- **Todo Block de produto documenta-se em `docs()`** (`BlockDocs`): categoria (o grupo da paleta do
  editor), resumo, como funciona, como ligar, limitações, fonte dos dados e um exemplo de template
  (`<node>` vira o id do nó). É o que a pessoa que monta o Flow lê no inspetor e na tela de Blocks.
  Documentação não é contrato: mudar `docs()` não muda a versão do Block.
- Outputs pensados para o template Mustache: **o número 0 não é falso** no JMustache, então quem
  precisa de "tem ou não tem" expõe um booleano (`hasActive`, `monitored`) ou uma lista vazia.

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

### `report-area` — Área do relatório (CAR ou polígono)

Começa um relatório que serve tanto para um imóvel do CAR quanto para qualquer polígono. Código:
`blocks.car.ReportAreaBlock`. Sem `DataRequirement` (com polígono só precisa do PostGIS); um código
CAR numa instância sem os dados do car-agent-app falha dizendo isso.

| Input | Tipo | |
|---|---|---|
| `codImovel` | string, opcional | código do SICAR; informe este **ou** `geometry` |
| `geometry` | geometry, opcional | GeoJSON em lon/lat (SIRGAS 2000 ou WGS 84): Polygon, MultiPolygon, Feature ou FeatureCollection |
| `name` | string, opcional | nome para mostrar quando é polígono |

| Output | Tipo | Conteúdo |
|---|---|---|
| `source` / `isCar` | string / boolean | `car` ou `polygon` |
| `codImovel` | string | o código; nulo para polígono. Ligue nos Blocks do CAR e marque esses nós como opcionais (ver ARCHITECTURE §47) |
| `label` | string | o código CAR, o `name` ou "Área informada" |
| `geometry`, `areaHa`, `centroidLat`, `centroidLon`, `bbox`, `vertices` | | como em `car-property-geometry` |

Com código, lê a geometria vigente como o `car-property-geometry`. Com polígono: `ST_MakeValid`, só
a parte poligonal, partes sobrepostas unidas (`ST_UnaryUnion`); a FeatureCollection vira uma
GeometryCollection das suas geometrias. Falha com os dois ou nenhum, com polígono sem área e com
coordenadas fora de ±180/±90 (UTM, por exemplo). No template, as seções do CAR ficam dentro de
`{{#area.isCar}}...{{/area.isCar}}`.

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

## 3. Blocks do BR Geo (fonte: `apps/br-geo-agent-app`, tabelas `gold.br_geo_agent_app_*`)

Quase todos recebem uma **área** (`geometry`, GeoJSON em SIRGAS 2000), em geral
`car-property-geometry.geometry`: é aqui que o Flow encadeia Blocks. A área passa por
`ST_MakeValid` e só a parte poligonal é analisada. Leem as linhas vigentes (`valid_to IS NULL`) e
medem tudo em hectares geodésicos. Timeout de 30 s (consulta 25 s).

| Block | Entradas | Saídas principais | Tabelas |
|---|---|---|---|
| `brgeo-municipalities` | `geometry` | `municipalities[] {ibgeCode, name, uf, intersectionHa, percentOfArea}`, `main`, `mainIbgeCode` (number, para ligar em Blocks por município), `mainUf` (sigla, para o `brgeo-crop-progress`), `count`, `areaHa`, `edition` | `ibge_municipality` (edição mais recente) |
| `brgeo-biomes` | `geometry` | `biomes[] {code, name, intersectionHa, percentOfArea}`, `mainBiome`, `count`, `areaHa`, `edition` | `ibge_biome` |
| `brgeo-embargoes` | `geometry`, `includeInactive?` (default true) | `embargoes[]` (até 100, ativos primeiro) `{term, series, seqTad, status, active, lifted, cancelled, formStatus, embargoedAt, liftedAt, liftType, changedAt, areaType, deterProdes, embargoedAreaHa, overlapHa, matchedBy, uf, municipalityIbge, processNumber, infractionNotice, judicialDecisions}`, `count`, `activeCount`, `hasActive`, `activeOverlapHa`, `lastEmbargoAt`, `truncated` | `ibama_embargo`, `ibama_embargo_decision` |
| `brgeo-deter-alerts` | `geometry`, `dateFrom?`, `dateTo?` | `alerts[]` (até 200, mais novos primeiro), `byClass[] {className, count, overlapHa, firstDate, lastDate}`, `count`, `totalOverlapHa`, `lastAlertDate`, `monitored`, `coverage[]`, `truncated` | `inpe_deter_alert`, `ibge_biome` |
| `brgeo-prodes` | `geometry`, `yearFrom?` | `byYear[] {product, productName, year, polygons, deforestedHa}`, `byProduct[] {..., firstYear, lastYear, deforestedHa, sinceCutoffHa}`, `count`, `maxDeforestedHa`, `lastYear`, `monitored`, `coverage[]` (com `lastYear` carregado) | `inpe_prodes_increment`, `ibge_biome` |
| `brgeo-crop-production` | `municipalityIbge` (number), `year?`, `limit?` (15) | `year`, `crops[] {product, plantedHa, harvestedHa, quantity, quantityUnit, yieldKgHa, valueThousandBrl, shareOfHarvestedPercent}`, `count`, `totalHarvestedHa`, `totalValueThousandBrl`, `truncated`, `uf` (do código IBGE, para o `brgeo-crop-progress`) | `ibge_pam_value`, `ibge_pam_product` (SIDRA 5457) |
| `brgeo-zarc-risk` | `municipalityIbge` (number), `crop`, `season?` (a mais recente), `soil?`, `cultivarGroup?`, `limit?` (30) | `found`, `message`, `crop`, `season`, `uf`, `municipality`, `ordinance`, `ordinanceDate`, `rows[] {croppingSystem, climateType, cultivarGroup, soil, risk[36], cells[36] {period, risk, label}, indicatedPeriods, lowestRisk, windows[] {risk, fromPeriod, toPeriod, from, to, label}}`, `count`, `truncated`, `periods[36] {period, from, to, label}`, `availableCrops` | `zarc_risk` (SISZARC) |
| `brgeo-crop-progress` | `uf` (sigla ou nome), `crop?`, `operation?` (`sowing`/`harvest`), `maxAgeDays?` (60) | `found`, `message`, `state`, `latestWeekEnd`, `items[] {crop, season, operation, operationLabel, weekEnd, pctDone, previousWeekPct, weekChangePoints, lastYearPct, avg5yPct, vsAvg5yPoints, series[] {weekEnd, pctDone}}`, `count`, `availableStates` | `conab_crop_progress` |

Regras de cada um:

- **Embargos.** Situação a partir do próprio termo: `sit_cancelado = 'S'` é cancelado,
  `sit_desembargo = 'S'` é desembargado e o resto é ativo. Metade dos termos não tem polígono, só
  coordenada: esses entram quando o ponto cai dentro da área (`matchedBy = point`, `overlapHa`
  vazio). Sem dados pessoais (o BR Geo não grava nome nem CPF/CNPJ).
- **DETER e PRODES: "não monitorado" não é "sem alerta".** `coverage` diz, por produto, se o bioma do
  produto toca a área e se o BR Geo carregou o produto. `monitored` só é verdadeiro quando os dois
  valem. A cobertura usa o bioma do IBGE como aproximação: o DETER Amazônia cobre, na verdade, a
  Amazônia Legal. DETER: `deter_amz` (Amazônia) e `deter_cerrado` (Cerrado).
- **PRODES: produtos nunca são somados.** Só os produtos por bioma entram; `prodes_legal_amz` fica de
  fora porque repete o `prodes_amazon`. `sinceCutoffHa` soma os anos PRODES a partir de 2009, que
  começa em agosto de 2008, logo depois do marco do Código Florestal (22/07/2008).
- **ZARC.** Risco por decêndio do ano (1 = 1 a 10/jan ... 36 = 21 a 31/dez): 0 é "não indicado"; 20,
  30 ou 40 é a probabilidade (%) de perda. Uma linha por manejo, tipo de clima, grupo de cultivar e
  classe de solo (AD1 a AD6). `windows` junta decêndios seguidos com o mesmo risco, inclusive através
  da virada do ano (soja: outubro a janeiro). A cultura é casada sem acento nem caixa ("milho 2a
  safra" acha "Milho 2ª safra"). Sem `season`, vale a safra mais recente que tem a cultura no
  município: as de calendário (`2026/2027`) primeiro, depois `Sem Safra`, `Perenes`... **Consulta:** a
  tabela não tem índice por município, então o Block lista os pares (safra, cultura) com um *skip
  scan* recursivo no índice de escopo `(season, crop, ...)` e lê a cultura nas suas safras e na UF do
  município (dois primeiros dígitos do código IBGE), que esse índice restringe.
- **CONAB.** A CONAB grava frações (0,452) e nomeia a UF por extenso: o Block devolve percentuais com 1
  casa e aceita a sigla do CAR. Por cultura e operação vale a safra mais nova; `previousWeekPct` é a
  semana anterior (até 10 dias antes), `lastYearPct` a mesma semana um ano antes (357 a 371 dias) e
  `avg5yPct` a média de 5 anos que a CONAB imprime para a semana. Operações sem semana nos últimos
  `maxAgeDays` dias saem (uma colheita que acabou em julho). Só os principais estados produtores.
- **PAM.** Tabela 5457 (lavouras temporárias e permanentes). Os totais vêm da linha `Total` (produto
  0). Subtotais como `Café (em grão) Total` são pulados para a mesma lavoura não contar duas vezes.
  Valores sigilosos ou ausentes (`X`, `...`, `-`) saem vazios.

**Verificado no banco de dev (2026-10-26)** com `AC-1200302-7ECA40E36E154380BC739F9CB7E33DE0`
(IRU, 34.836 ha, Feijó/AC, encadeado a partir de `car-property-geometry`): 99,79% em Feijó e 0,21%
em Envira/AM, 100% Amazônia, 28 termos de embargo (24 ativos, 760 ha), 76 alertas DETER, PRODES
2008 a 2014. Tempos por Block: 60 a 730 ms. O laudo completo com os 12 nós roda em cerca de 3 s.

Estado dos dados no dev nessa data: DETER só `deter_amz`; PRODES só `prodes_amazon` de 2008 a 2014
(carga parcial). O `coverage[].lastYear` mostra isso no relatório.

## 3.1 Blocks do BR Weather (fonte: `apps/br-weather-agent-app`, tabelas `gold.br_weather_agent_app_*`)

Clima da área: NASA POWER (temperatura, umidade, vento, radiação) e CHIRPS v3 (chuva). Recebem a
área (`geometry`) e um período: `from`/`to`, ou os últimos `days` dias (padrão 30, até 366) até o
último dia carregado. Código em `blocks.brweather`.

| Block | Entradas | Saídas principais | Tabelas / arquivos |
|---|---|---|---|
| `brweather-daily-weather` | `geometry`, `from?`, `to?`, `days?` | `found`, `message`, `from`, `to`, `latestDay`, `cells[] {lat, lon, elevationM, weightPercent}`, `nearestCell`, `days[] {date, t2m, t2mMax, t2mMin, rh2m, ws2m, radiation, precipitation}`, `daysWithData`, `meanT2m`, `maxT2m`/`maxT2mDate`, `minT2m`/`minT2mDate`, `meanRh2m`, `meanWs2m`, `meanRadiation`, `powerPrecipitationMm`, `degreeDays` (base 10 °C), `hotDays` (máx. ≥ 35 °C), `coldNights` (mín. ≤ 3 °C), `chart` (PNG 900 × 300) | `nasa_power_cell`, `nasa_power_daily` |
| `brweather-rainfall` | `geometry`, `from?`, `to?`, `days?` | `found`, `message`, `from`, `to`, `latestDay`, `pixels`, `wholePixels`, `days[] {date, rainMm, preliminary}`, `daysWithData`, `missingDays`, `totalMm`, `meanDailyMm`, `maxDayMm`/`maxDayDate`, `rainyDays` (≥ 1 mm), `longestDrySpell`, `preliminaryDays`, `chart` (barras, PNG 900 × 260) | `chirps_day` + os recortes GeoTIFF no lake |

Regras:

- **NASA POWER.** As células (0,5° × 0,625°, uns 55 × 65 km) que tocam a área entram com peso igual à
  parte da área em cada uma; área que não toca célula nenhuma usa a mais próxima até 1° (`nearestCell`).
  Até 60 células. Média ponderada por dia, ignorando o valor ausente (não vira zero). Unidades:
  °C, %, m/s, MJ/m²/dia, mm. A chuva do POWER (`powerPrecipitationMm`, MERRA-2) é só conferência: a
  chuva do relatório é a do CHIRPS, como no BR Weather.
- **CHIRPS.** O gold guarda só o catálogo (um recorte do Brasil por dia, 0,05°, EPSG:4326); os pixels
  estão em GeoTIFF no lake do host (`BRWEATHER_CHIRPS_DIR`). O Block abre o arquivo de cada dia e lê
  só os pixels da área com o mesmo leitor dos COGs do Sentinel-2 (`HttpCogReader` sobre
  `LocalFileRangeFetcher`, que agora lê float32 com predictor 3 e CRS geográfico). Média dos pixels
  com centro dentro da área; área menor que um pixel usa os pixels da caixa dela (`wholePixels =
  false`). Dia sem arquivo conta em `missingDays`; um buraco nos dados interrompe a contagem do
  veranico (`longestDrySpell`). `preliminary` marca dias da série preliminar, trocados pela final
  depois.
- **Segurança:** só arquivos sob `reports.brweather.chirps-dir` (`REPORTS_CHIRPS_DIR`, padrão
  `/opt/infinitestack/var/lake/bronze/br-weather-agent-app/chirps`, o mesmo do BR Weather) são lidos;
  um `path` da tabela fora dele falha o Block. Assim o Block não vira um leitor de arquivos do host.
- Timeouts: 30 s (POWER) e 60 s (CHIRPS, um arquivo por dia; um ano leva alguns segundos).

**Teste:** `SourceBlocksSyntheticDbTest` roda o SQL dos Blocks de ZARC, CONAB e BR Weather contra
tabelas sintéticas com as colunas dos apps de origem, num PostGIS descartável
(`REPORTS_TEST_DB_URL`); o CHIRPS lê o recorte de teste `cog/rain-4326.tif` (float32, predictor 3,
gerado com GDAL 3.8). Se as tabelas já existem no banco, o teste é pulado em vez de mexer nelas.

## 4. Blocks do Satellite Hub (fonte: agent app `satellite-hub`, tabelas `gold.satellite_hub_*`)

### `sathub-index-series` — Série de índice de vegetação

Lê o que o Satellite Hub **já calculou e publicou** no gold (estatísticas por imagem e por área
cadastrada nele). Cada índice é de uma coleção: Sentinel-2 L2A (`ndvi`, `evi`...), Landsat 8/9
(`ndvi_landsat`, `ndmi_landsat`) ou Sentinel-1 RTC (`rvi_s1`, radar, escala 0 a 4); só existem
os que o admin do Satellite Hub habilitou. Não pede processamento novo (ver [INTEGRATIONS.md](INTEGRATIONS.md)).

| Input | Tipo | |
|---|---|---|
| `geometry` | geometry, opcional | combina as áreas do Satellite Hub que tocam a geometria (até 50, maior parte dentro primeiro) |
| `areaId` | number, opcional | uma área do Satellite Hub pelo id (no lugar da geometria) |
| `index` | string, opcional | `ndvi` (default), `evi`, `savi`, `ndre`, `gndvi`, `ndmi`, `ndwi`, `nbr` (Sentinel-2), `ndvi_landsat`, `ndmi_landsat` (Landsat), `rvi_s1` (Sentinel-1) ou outro habilitado |
| `days` | number, opcional | tamanho da série, contado para trás a partir de `to` ou, sem ele, da imagem mais recente; default 365 |
| `from` | date, opcional | primeiro dia da série; com ele, `days` é ignorado |
| `to` | date, opcional | último dia da série; default até a imagem mais recente |
| `minValidPercent` | number, opcional | descarta imagens com menos desse % de área sem nuvem; default 50 |

| Output | Tipo | Conteúdo |
|---|---|---|
| `index` | object | `{slug, name, description, collection, collectionName}`; `collection` é o id do Satellite Hub (`sentinel-2-l2a`, `landsat-c2-l2`, `sentinel-1-rtc`; vazio no banco = `sentinel-2-l2a`) |
| `areas` / `areaCount` | array / number | `{id, farmName, blockName, parcelName, areaHa, insideHa}` |
| `series` | array | um ponto por data de imagem: `{date, mean, min, max, p10, p90, validAreaPercent, areas}` |
| `count`, `latestDate`, `latestMean`, `periodMean`, `periodMin`, `periodMax` | | resumo da série |
| `chart` | string | PNG `data:` (900 × 300): média ponderada em linha sobre a faixa p10-p90 |
| `truncated` | boolean | mais de 50 áreas na geometria |

Por data, as áreas são combinadas com média **ponderada pelos pixels válidos**. O Satellite Hub
grava as geometrias em WGS 84 (4326), e a entrada é transformada para esse sistema. É preciso
`geometry` ou `areaId`, senão o Block falha com mensagem clara.

No dev (2026-10-26), as 23 mil áreas do Satellite Hub ficam no norte da França (dados de teste). Por
isso nenhum imóvel CAR as cruza e o teste usa `areaId = 16991` ou um quadrado lá. A série tem 41
imagens de 2026-04 a 2026-10.

### `sathub-latest-image` — Última imagem Sentinel-2 da área

O Satellite Hub só processa as áreas cadastradas nele, em agenda, e guarda estatísticas, não
imagens. Este Block vai **à mesma fonte que o Satellite Hub usa** (STAC Earth Search da Element 84 +
COGs Sentinel-2 L2A na AWS), lê só a janela da área e calcula os índices com as **definições do
Satellite Hub** (`gold.satellite_hub_analytics_indices`). Funciona para qualquer área, cadastrada ou
não no Satellite Hub. Código: `blocks.sathub.SatelliteHubLatestImageBlock` + pacote
`blocks.sathub.imagery` (leitor de COG por HTTP Range, UTM, STAC, fórmulas, máscara, render).

| Input | Tipo | |
|---|---|---|
| `geometry` | geometry, obrigatório | Polygon/MultiPolygon lon/lat (SIRGAS 2000, tratado como WGS 84); em geral `car-property-geometry.geometry` |
| `indices` | string, opcional | slugs do Satellite Hub **da coleção Sentinel-2** separados por vírgula; default `ndvi,evi,ndmi` |
| `maxCloudPercent` | number, opcional | nuvem da cena (`eo:cloud_cover`); default 40 |
| `maxAreaCloudPercent` | number, opcional | nuvem máxima **dentro da área**; acima disso a cena é pulada e a anterior tentada; default 30 |
| `maxAgeDays` | number, opcional | janela de busca; default 120 |
| `sizePx` | number, opcional | maior lado dos PNGs, 200 a 1600; default 800 |

| Output | Tipo | Conteúdo |
|---|---|---|
| `found` | boolean | achou cena com poucas nuvens sobre a área |
| `message` | string, opcional | por que não achou (em inglês, como os outros textos de Block) |
| `scene` | object, opcional | `{id, acquiredAt, date, platform, mgrsTile, cloudCoverPercent, stacUrl}` |
| `acquiredDate`, `daysAgo` | date / number, opcionais | data da aquisição (America/Sao_Paulo) e dias até hoje |
| `coveragePercent`, `areaCloudPercent` | number, opcionais | parte da área dentro da cena; parte sob nuvem/sombra/sem dado |
| `pixelSizeM`, `widthPx`, `heightPx` | number, opcionais | grade das imagens |
| `trueColor` | string, opcional | PNG `data:` em cor verdadeira, com o contorno da área |
| `imageCrs` | string, opcional | CRS dos PNGs: a zona UTM da cena, ex. `EPSG:32721` |
| `imageTransform` | array, opcional | transformação afim dos PNGs em `imageCrs`, ordem GDAL/rasterio `[pixelSizeM, 0, minX, 0, -pixelSizeM, maxY]` (canto superior esquerdo do pixel); serve para georreferenciar o recorte (world file, GeoTIFF) |
| `imageBounds` | array, opcional | `[minLon, minLat, maxLon, maxLat]` em graus (WGS 84): caixa em torno dos quatro cantos dos PNGs, para sobrepor a imagem num mapa lon/lat (ex. `L.imageOverlay`) |
| `indices` | array | na ordem pedida (vazia sem imagem): `{slug, name, description, image, mean, min, max, stddev, p10, p50, p90, validPixels, validPercent, rangeMin, rangeMax, legend [{color, value, label}]}` |
| `candidatesTried` | number | cenas cuja nuvem sobre a área foi conferida |
| `skipped` | array | cenas puladas por nuvem: `{id, date, areaCloudPercent}` |

Regras:

- **Mesma metodologia do Satellite Hub:** reflectância = DN × `scale` (0,0001) **sem** o offset;
  nuvem pela banda SCL (4, 5, 6, 7 = limpo; o resto e "sem dado" contam como nuvem); fórmula,
  `clamp_min/max` e `mask_clouds` do índice. Faixa de cores: `display_min/max`; sem ela, [0, 1] para
  ndvi, evi, savi, gndvi, ndre e [-1, 1] para o resto. Paleta de água para ndwi/ndmi, de vegetação
  para os outros.
- **Escolha da cena:** busca até 20 cenas (mais novas primeiro); o `SceneSelector` prefere a mais nova
  que cobre a área inteira; até 4 candidatas. De cada uma lê só a SCL (vizinho mais próximo) e mede a
  nuvem dentro da área; da aceita, lê as bandas em paralelo (6 threads, bilinear).
- **Grade:** UTM da cena (EPSG da cena), caixa da área + 5% (mínimo 100 m), pixel de 10 m ou múltiplo
  de 10 para o maior lado caber em `sizePx`. Estatísticas sobre os pixels dentro da área e sem nuvem;
  `validPercent` é sobre os pixels dentro da área.
- **Sem imagem não é falha:** `found = false` com `message` e `skipped`, e o relatório renderiza.
  Índice desconhecido ou desabilitado é falha, com a lista dos disponíveis. Índice de outra coleção
  (`ndvi_landsat`, `rvi_s1`) também: a fórmula dele usa bandas de outro sensor (o `nir08` do Landsat
  não é o `nir08` do Sentinel-2), então só os de `collection = sentinel-2-l2a` (ou vazia) entram. Erro de rede/leitura é
  falha com mensagem legível.
- Timeout próprio de 110 s (a execução tem 120 s). O gate do catálogo é a tabela de índices: o Block
  aparece quando o Satellite Hub está instalado.
- **Rede:** precisa de HTTPS de saída do host para `earth-search.aws.element84.com` (STAC, configurável
  em `reports.sentinel.stac-url` / `REPORTS_SENTINEL_STAC_URL`) e `sentinel-cogs.s3.us-west-2.amazonaws.com`
  (COGs). É o único Block que sai da rede da instância.
- Teste unitário com STAC e COG falsos (`SatelliteHubLatestImageBlockTest`; as definições de índice
  entram pelo método package-private `indexRows()`, porque não há banco em memória nos testes); teste
  real com `SENTINEL_NETWORK_TEST=1` + `CAR_TEST_DB_*` (`SatelliteHubLatestImageNetworkTest`, grava os
  PNGs em `reports/target/latest-image/`).
- **Verificado com dados reais (2026-10-26):** em `AC-1200302-7ECA40E36E154380BC739F9CB7E33DE0`
  (34.836 ha), as duas cenas de 25/10 tinham nuvem demais sobre o imóvel e foram puladas; a de 10/10
  (S2B, 19MCM) entrou com 0% de nuvem na área, pixel de 40 m (618 x 667) e NDVI médio de 0,817. O Block
  levou de 12 a 51 s e o PDF saiu com 7 páginas. Num imóvel de 3.097 ha
  (`AC-1200179-BA98DAA4BFE841B9877C11E635B24695`), com pixel de 20 m e 3 cenas avaliadas, levou 32 s.
  O tempo é dominado pela banda de rede até a AWS (us-west-2): daqui, cerca de 330 KB/s. Cada cena
  avaliada lê a SCL, e a aceita lê as bandas dos índices (tiles de 1024 px; um imóvel pequeno em 10 m
  ainda baixa de 1 a 4 tiles por banda).

## 5. Block do Skills Lab (fonte: scripts Python dos projetos do Skills Lab)

### `skillslab-python` — Script Python (Skills Lab)

Roda **qualquer** script Python do Skills Lab na hora do relatório, por um workflow do projeto, e
devolve o objeto JSON que o script imprimiu. Código: `blocks.skillslab.SkillsLabPythonBlock`; como
funciona, o que a instância precisa e os limites em [INTEGRATIONS.md](INTEGRATIONS.md) §2.

| Input | Tipo | |
|---|---|---|
| `projectId` | string, obrigatório | projeto do Skills Lab |
| `workflow` | string, obrigatório | id do workflow do projeto que roda o script, liberado para o app em `reports-workflows.json` |
| `params` | object, opcional | JSON fixo mesclado no `--context` do script |
| `text`, `number`, `date`, `geometry`, `data`, `items` | string, number, date, geometry, object, array; opcionais | cada um, se ligado, entra no `--context` com o próprio nome (e vence a chave igual de `params`) |
| `timeoutSeconds` | number, opcional | 5 a 110, padrão 100; depois disso o run é parado e o Block falha |

| Output | Tipo | Conteúdo |
|---|---|---|
| `result` | object, opcional | o objeto da última linha `IS_REPORTS_RESULT <json>` do stdout |
| `hasResult` | boolean | o script imprimiu um resultado |
| `status` | string | `SUCCESS` ou `WARNING`; qualquer outro final falha o Block com o fim do log |
| `triggerId` | string | id do run (Workloads) |
| `log` | string | últimos 4.000 caracteres da saída, sem as linhas de resultado |
| `durationSeconds` | number | do pedido ao fim do run, com a fila |

Antes de iniciar o run, o Block confere na lista do host (`GET …/workflows`) se o workflow está
liberado para o app e ainda existe no projeto; se não, falha dizendo o arquivo a editar e o que o
projeto tem liberado, sem iniciar nada.

Sem `DataRequirement`: está sempre disponível para liberar. O que controla o que roda é a liberação
do workflow na instância e o acesso do usuário ao workflow (`onBehalfOf`, conferido pelo back).
Timeout próprio de 118 s, acima do `timeoutSeconds`, para o Block ainda parar o run e ler o log.
