# REPORTS — Blocks: o que vem depois

Desenho de duas funcionalidades que **não** entram na Fase 8: os **Dataset Blocks** (consulta livre
sobre uma base liberada, parte B) e o **prazo de atualização dos dados** (parte 2). A Fase 8 faz
só o estilo A: Blocks de contrato fixo, escritos em código, como os do CAR em [BLOCKS.md](BLOCKS.md).
Este documento parte do código em `reports/` (seção 47 do [ARCHITECTURE.md](ARCHITECTURE.md)) e
diz o que muda nele. Nada aqui está implementado.

| Estilo | O que é | Quem define a consulta | Estado |
|---|---|---|---|
| **A — Block de função** | contrato fixo (`car-property`, `car-overlaps`…), SQL escrito e revisado em código | o desenvolvedor | Fase 8 |
| **B — Dataset Block** | o admin libera uma **tabela do catálogo**; o usuário monta, dentro do Flow, a consulta que quiser sobre ela | o usuário, dentro do que o admin expôs | este documento |

Os dois convivem: A para o que precisa de regra de negócio (vigência, agregado × partes, mapa), B
para perguntas que ninguém previu.

---

# Parte B — Dataset Blocks

## B.1 Conceito

Um **dataset** é uma tabela (ou view) do catálogo que um ADMIN/SCICROP liberou para consulta,
dizendo **quais colunas** ficam visíveis e **o que** se pode fazer com cada uma. Liberado, ele
aparece na lista de Blocks como `ds-<slug>`. Num nó desse Block, o usuário escreve uma **consulta
declarativa** (JSON, montada pela UI): colunas, filtros, agrupamento, agregados, ordem e limite. O
app traduz a consulta para SQL **ele mesmo**, só com identificadores da allowlist do dataset e
valores sempre como parâmetros. O usuário nunca escreve SQL.

Exemplos:

| Pergunta | Dataset | Consulta |
|---|---|---|
| Imóveis CAR do município X com status AT e área > 100 ha | `ds-car-imovel-vigente` | filtros `cod_municipio = {{inputs.municipio}}`, `ind_status = 'AT'`, `num_area > 100`; colunas `cod_imovel, ind_tipo, num_area`; ordem `num_area desc`; limite 200 |
| Soma da área de Reserva Legal por município | `ds-car-feicao-vigente` | filtro `camada = 'RESERVA_LEGAL'`, filtro `uf = 'MT'`; `groupBy cod_municipio`; agregado `sum(area_ha)` |
| Embargos IBAMA que intersectam a geometria do nó anterior | `ds-ibama-embargo` | filtro espacial `geom intersects {{imovel.geometry}}`; colunas `num_auto, dat_embargo, des_infracao`; agregado `intersection_area_ha` |

**Uma tabela por dataset, sem join.** As tabelas do CAR são normalizadas (`imovel`, `imovel_versao`,
`feicao`, `tema`…). Perguntas como as duas primeiras pedem uma tabela ou view **analítica**
desnormalizada no gold (ex.: `gold.car_agent_app_imovel_vigente`, `gold.car_agent_app_feicao_vigente`),
publicada pelo agent app da fonte e registrada no catálogo. Join livre fica fora (ver B.10).

## B.2 Modelo de dados

Nova tabela, criada pelo `SchemaInitializer` como as outras:

```sql
CREATE TABLE IF NOT EXISTS apps.reports_dataset (
    id            BIGSERIAL    PRIMARY KEY,
    slug          VARCHAR(80)  NOT NULL UNIQUE,      -- kebab-case; o Block é 'ds-' || slug
    label         VARCHAR(200) NOT NULL,
    description   TEXT,
    schema_name   VARCHAR(63)  NOT NULL,
    table_name    VARCHAR(63)  NOT NULL,
    source_id     VARCHAR(120),                      -- opcional: fixa a fonte do catálogo
    columns       JSONB        NOT NULL,             -- colunas expostas (abaixo)
    base_filter   JSONB,                             -- filtros sempre aplicados, invisíveis ao usuário
    max_rows      INT          NOT NULL DEFAULT 1000,
    default_limit INT          NOT NULL DEFAULT 100,
    timeout_ms    INT          NOT NULL DEFAULT 15000,
    require_filter BOOLEAN     NOT NULL DEFAULT TRUE, -- guarda de custo (B.6)
    revision      INT          NOT NULL DEFAULT 1,   -- +1 a cada mudança de exposição
    created_by    VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(120),
    updated_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ                        -- soft delete: execuções antigas apontam para cá
);
```

`columns` lista só o que o admin expõe. Coluna fora da lista não existe para o usuário:

```json
[
  {"name": "cod_imovel",    "type": "STRING",   "label": "Código do imóvel", "roles": ["output", "filter"], "indexed": true},
  {"name": "cod_municipio", "type": "NUMBER",   "label": "Município (IBGE)", "roles": ["output", "filter", "group"], "indexed": true},
  {"name": "ind_status",    "type": "STRING",   "label": "Status",           "roles": ["output", "filter", "group"]},
  {"name": "num_area",      "type": "NUMBER",   "label": "Área declarada (ha)", "roles": ["output", "filter", "aggregate"]},
  {"name": "geom",          "type": "GEOMETRY", "label": "Geometria",        "roles": ["geometry"], "srid": 4674, "indexed": true},
  {"name": "cpf_cnpj",      "type": "STRING",   "label": "CPF/CNPJ",         "roles": ["filter"], "filterOps": ["eq"]}
]
```

| Papel | Permite |
|---|---|
| `output` | aparecer em `select` e em `orderBy` |
| `filter` | aparecer em `where` (com `filterOps` opcional restringindo os operadores) |
| `group` | aparecer em `groupBy` |
| `aggregate` | `sum`/`avg`/`min`/`max` (`count` e `count_distinct` valem para `output`) |
| `geometry` | filtros espaciais e agregados de área; sai como GeoJSON só se também tiver `output` |

Uma coluna só com `filter` (o `cpf_cnpj` acima) serve para achar linhas sem nunca aparecer no
resultado: é assim que se expõe um dado sensível sem vazar. `indexed` é declarado pelo admin (o
app confere no `pg_indexes` ao salvar e avisa se não bater) e alimenta a guarda de custo.

**Gate do catálogo, reaproveitado.** O dataset vira um `BlockDefinition` cujos `dataRequirements`
saem dele mesmo: `DataRequirement.table(schema_name, table_name, <colunas expostas + colunas do
base_filter>)`. O `BlockAvailabilityService` não muda: tabela ausente, fonte inativa ou coluna
sumida dão `MISSING_DATA`, e o prazo da parte 2 vale igual. Ao salvar, o app também confere o
**tipo** de cada coluna contra `CatalogColumn.type` (o catálogo diz `text`, o dataset não pode
dizer `NUMBER`).

**Liberação, reaproveitada.** Criar ou editar um dataset não o libera. A liberação é a mesma de
hoje: `POST /api/blocks/ds-<slug>/release`, gravada em `apps.reports_block_release` com o
diagnóstico. Editar a exposição incrementa `revision` e **revoga** a liberação (o admin libera de
novo, vendo o diagnóstico novo). A versão do Block é `1.0.0+r<revision>`: o `1.0.0` é a versão do
motor de consulta e o `r` a revisão do dataset, e as duas entram em `block_versions` da execução.

## B.3 A consulta (`query`) no nó do Flow

Hoje um nó é `{id, block, values}`. Ele ganha `config`, um objeto livre que só Blocks
configuráveis aceitam (para os outros, `config` não vazio é erro de validação):

```json
{
  "id": "imoveisGrandes",
  "block": "ds-car-imovel-vigente",
  "config": {
    "query": {
      "select": ["cod_imovel", "ind_tipo", "num_area"],
      "where": [
        {"column": "cod_municipio", "op": "eq", "param": "municipio"},
        {"column": "ind_status",    "op": "eq", "value": "AT"},
        {"column": "num_area",      "op": "gt", "value": 100}
      ],
      "orderBy": [{"column": "num_area", "dir": "desc"}],
      "limit": 200
    }
  },
  "values": {}
}
```

Um filtro tem **`value`** (literal fixo, gravado na versão) ou **`param`** (vira uma **porta de
input** do nó, com o tipo da coluna). As portas `param` são ligadas como qualquer outra: a um
input do Report, à saída de outro nó ou a um valor fixo em `values`. Assim o `FlowValidator` e as
conexões de hoje continuam valendo, sem sintaxe nova.

Agrupamento e filtro espacial:

```json
{"query": {
  "where": [
    {"column": "camada", "op": "eq", "value": "RESERVA_LEGAL"},
    {"column": "uf",     "op": "eq", "param": "uf"}
  ],
  "groupBy": ["cod_municipio"],
  "aggregates": [
    {"fn": "sum",   "column": "area_ha", "as": "reservaLegalHa"},
    {"fn": "count", "as": "feicoes"}
  ],
  "orderBy": [{"column": "reservaLegalHa", "dir": "desc"}],
  "limit": 1000
}}
```

```json
{"query": {
  "select": ["num_auto", "dat_embargo", "des_infracao"],
  "where": [{"column": "geom", "op": "intersects", "param": "area"}],
  "aggregates": [{"fn": "intersection_area_ha", "column": "geom", "with": "area", "as": "areaEmbargadaHa"}],
  "groupBy": ["num_auto", "dat_embargo", "des_infracao"],
  "limit": 100
}}
```

Aqui `area` é uma porta `GEOMETRY`, ligada em `imovel.geometry` (saída do `car-property-geometry`).

**Operadores (allowlist fechada):**

| `op` | Colunas | SQL gerado | Limites |
|---|---|---|---|
| `eq` `ne` `lt` `lte` `gt` `gte` | escalares | `"c" = ?` … | — |
| `in` / `notIn` | escalares | `"c" = ANY(?)` (array bind) | até 1000 valores |
| `between` | número, data | `"c" BETWEEN ? AND ?` | — |
| `isNull` / `notNull` | todas | `"c" IS [NOT] NULL` | sem valor |
| `startsWith` | texto | `"c" LIKE ? ESCAPE '\'` (valor escapado + `%`) | ≥ 3 caracteres |
| `contains` | texto | `"c" ILIKE ? ESCAPE '\'` | só se o admin habilitar na coluna (custo) |
| `intersects` | geometria | `"g" && ST_GeomFromGeoJSON(?) AND ST_Intersects("g", ST_GeomFromGeoJSON(?))` | geometria de entrada válida, ≤ 50 mil vértices |
| `within` | geometria | `ST_Within("g", …)` | idem |
| `dwithin` | geometria | `ST_DWithin("g"::geography, …::geography, ?)` | metros, até 50 km |

Os filtros do `where` são **E** entre si. **OU** fica para depois; `in` cobre o caso mais comum.

**Agregados:** `count`, `count_distinct`, `sum`, `avg`, `min`, `max`; e, em coluna `geometry`,
`area_ha` (`sum(ST_Area(g::geography))/1e4`) e `intersection_area_ha` (área da interseção com uma
porta geometria). Sem `groupBy`, os agregados dão uma linha só.

**Regras de forma:** com `groupBy`, `select` só pode ter colunas agrupadas; `orderBy` só usa
colunas do `select` ou aliases de agregados; `as` segue o padrão das portas
(`[a-zA-Z][a-zA-Z0-9_]*`); `limit` padrão `default_limit`, teto `max_rows` do dataset (e um teto
global `reports.limits.dataset-max-rows`, 5000).

## B.4 Integração na engine

### Duas formas, e a escolhida

| | **(1) Um Block por dataset** (`ds-<slug>`), via provider | **(2) Um Block genérico** `dataset-query` com `config.dataset` |
|---|---|---|
| Gate e liberação | por dataset, com o `BlockService` de hoje | uma liberação para todos os datasets; o gate por dataset vira regra à parte |
| Disponibilidade | `BlockAvailabilityService` sem mudança (requisitos fixos do dataset) | requisitos dependem da config de cada nó; checagem por nó |
| Lista para o usuário | cada dataset aparece no "Choose a block" com nome próprio | um item só e um segundo seletor dentro do nó |
| Mudança no registry | `BlockRegistry` passa a compor providers | nenhuma |
| Versão registrada | `1.0.0+r<revision>` por dataset | a do motor; a revisão do dataset precisaria de campo próprio |

**Recomendação: (1).** O que dá valor ao estilo B é justamente o gate e a liberação **por base**,
e (1) os reaproveita sem nada novo. O custo é o registry deixar de ser fixo no boot, o que é uma
mudança pequena e localizada.

### Peças

- **`BlockProvider`** (`engine.block`): `Collection<ReportBlock> blocks()` e
  `Optional<ReportBlock> find(String id)`. O `BlockRegistry` passa a consultar uma lista de
  providers: `BeanBlockProvider` (os beans de hoje, validados no boot como agora) e
  `DatasetBlockProvider` (lê `apps.reports_dataset`, com cache invalidado quando um dataset é
  salvo). O prefixo `ds-` fica reservado: um bean com esse prefixo derruba o boot.
- **`ConfigurableBlock extends ReportBlock`**: `ReportBlock bind(Map<String,Object> config)`. Valida
  a config e devolve uma instância **ligada ao nó**, cujo `definition()` já tem as portas reais
  daquele nó. `DatasetBlock` implementa isso. O `FlowExecutor` não muda: ele recebe a instância
  ligada e chama `execute(inputs, metadata)` como sempre.
- **`FlowNode.config`** (novo campo, `Map<String,Object>`, default vazio). Vai no JSON da versão,
  então a consulta fica congelada na versão publicada (reprodutibilidade da Fase 7).
- **Resolução única do nó**: um helper `NodeBlocks.resolve(registry, node)` (estático ou
  componente) devolve `block` ou `block.bind(config)`. É usado pelo `FlowValidator` (no lugar do
  `registry.find(node.block())`), pelo `TemplateScope.of(...)` e pelo `BlockService.problemsFor`.
  Erros de `bind` entram na lista de problemas do validador com o prefixo do nó
  (`node 'imoveisGrandes': filter 3: column 'xyz' is not exposed by dataset car-imovel-vigente`).

### Portas do nó

| Porta | Tipo | Quando |
|---|---|---|
| inputs `param` | tipo da coluna (`GEOMETRY` para filtro espacial) | uma por `param` distinto; obrigatórias |
| `rows` | ARRAY | sempre; cada item é um objeto com as colunas do `select`/`groupBy` e os aliases dos agregados |
| `count` | NUMBER | sempre; linhas devolvidas (depois do limite) |
| `truncated` | BOOLEAN | sempre; havia mais linhas que o `limit` (busca `limit + 1`) |
| `aggregates` | OBJECT | só sem `groupBy` e com agregados: `{reservaLegalHa: 1234.5, feicoes: 87}` |
| `first` | OBJECT | sempre; a primeira linha ou `null`, para `{{q.first.cod_imovel}}` sem seção |

O `TemplateScope` aprende essas portas pelo `definition()` da instância ligada, sem código
específico. Para ajudar quem escreve o template, `TemplateScope.Field` ganha um opcional
`itemFields` (as colunas de `rows`/`first`), e a lista de variáveis oferece
`imoveisGrandes.rows[].num_area`. A validação estrita continua como hoje: fora de seção, `rows` e
`first` aceitam campos internos (são estruturados); dentro de seção, o render estrito pega o erro.

## B.5 Execução

`DatasetBlock.execute` compila a consulta e roda por `BlockQueries.read(timeout, jdbc -> ...)`:
pool somente leitura (`blockDataSource`), transação read-only e `SET LOCAL statement_timeout`. O
timeout é o menor entre `timeout_ms` do dataset e o que sobra do Block/execução.

O compilador (`engine.dataset.QueryCompiler`, puro, sem banco) recebe dataset + consulta + valores
das portas e devolve `CompiledQuery(sql, params)`:

```sql
SELECT "cod_imovel", "ind_tipo", "num_area"
FROM "gold"."car_agent_app_imovel_vigente"
WHERE ("valido_ate" IS NULL)                         -- base_filter do admin
  AND "cod_municipio" = ? AND "ind_status" = ? AND "num_area" > ?
ORDER BY "num_area" DESC
LIMIT 201
```

Geometria em `rows` sai como `ST_AsGeoJSON("geom", 6)`; com mais de 200 linhas, simplificada
(`ST_SimplifyPreserveTopology`) ou omitida, conforme o dataset. Datas saem ISO-8601 e números como
`Number`, que é o que `DataType.coerce` espera.

## B.6 Segurança

| Risco | Defesa |
|---|---|
| Injeção por identificador | o SQL só usa nomes vindos de `reports_dataset.columns`, conferidos contra o catálogo ao salvar, casados com `[a-z_][a-z0-9_]*` e sempre entre aspas duplas. Nada da consulta do usuário vira identificador sem passar pela allowlist |
| Injeção por valor | todo valor é parâmetro `?` (`in` como array bind, geometria como texto GeoJSON em `ST_GeomFromGeoJSON(?)`). `LIKE` com `%`/`_` escapados |
| Operador arbitrário | allowlist do B.3, por tipo de coluna, restringível por coluna (`filterOps`) |
| Resultado gigante | `LIMIT` sempre presente e ≤ `max_rows`; o compilador não gera SQL sem ele |
| Consulta cara | `statement_timeout` sempre; **filtro obrigatório** quando `require_filter`: ao menos um filtro de igualdade/`in`/`between` em coluna `indexed` ou um filtro espacial, senão erro de validação; `EXPLAIN (FORMAT JSON)` sem `ANALYZE` no **preview e no salvar do rascunho**, recusando custo acima de `reports.dataset.max-plan-cost` (custo do planner é estimativa, então serve como alarme, não como única barreira); o pool do `blockDataSource` limita a concorrência |
| Escrita | pool e transação somente leitura; o compilador só gera `SELECT` |
| Dado sensível | só colunas expostas existem; coluna só `filter` nunca sai; a máscara de inputs/outputs em `reports_execution_block` vale aqui |
| Fuga de vigência/escopo | `base_filter` do admin entra sempre, entre parênteses, antes dos filtros do usuário |
| Auditoria | ver abaixo |

**Auditoria.** Cada nó de dataset grava em `reports_execution_block` (num campo novo `details
JSONB`, o mesmo que a parte 2 usa para a data dos dados): `datasetId`, `revision`, o SQL com
placeholders, os parâmetros (com a máscara), linhas devolvidas, `truncated` e o custo estimado
quando houve `EXPLAIN`. A consulta em si já está na versão do Report. O log segue o formato do
`FlowExecutor` com `dataset=<slug> rows=<n>`.

## B.7 API

| Método e rota | Quem | Faz |
|---|---|---|
| `GET POST /api/datasets`, `GET PUT DELETE /api/datasets/{slug}` | ADMIN/SCICROP | cadastro; o `PUT` valida contra o catálogo, incrementa `revision` e revoga a liberação |
| `GET /api/datasets/candidates` | ADMIN/SCICROP | tabelas do catálogo com colunas e tipos, para montar um dataset |
| `POST /api/blocks/{id}/release` (já existe) | ADMIN/SCICROP | libera `ds-<slug>` |
| `POST /api/blocks/{id}/resolve` `{config}` | quem edita o Report | portas do nó e problemas da consulta, sem rodar |
| `POST /api/blocks/{id}/preview` `{config, values}` | quem edita o Report | roda com `LIMIT 20`, devolve linhas, `truncated` e custo |

`GET /api/blocks` passa a devolver, para Blocks configuráveis, um `configKind: "dataset-query"` e
as colunas expostas com papéis, que é o que a UI precisa para o construtor.

## B.8 UI

- **Admin → Datasets** (nova seção ao lado de Blocks): escolher a tabela do catálogo, marcar
  colunas e papéis numa grade (`output`/`filter`/`group`/`aggregate`/`geometry`, `indexed`),
  `base_filter` com o mesmo editor de filtros, limites, e o diagnóstico do catálogo. Liberar e
  revogar como nos Blocks.
- **Card do nó no `flow-editor.js`**, quando `configKind = dataset-query`, ganha um construtor:
  1. **Colunas**: chips das colunas `output`;
  2. **Filtros**: linhas *coluna → operador (só os permitidos) → valor fixo ou "ligar a…"*; "ligar
     a" cria a porta `param` e cai no mapeamento de inputs que o card já tem;
  3. **Agrupar e agregar**: colunas `group`, agregados `fn(coluna) as alias`;
  4. **Ordem e limite** (o limite mostra o teto do dataset);
  5. **Prévia**: botão que chama `preview` com os valores fixos/de teste e mostra as 20 primeiras
     linhas numa tabela, mais o aviso de truncado ou de custo.
- A cada mudança, o editor chama `resolve` e atualiza as portas de output do card e a lista de
  variáveis do template (o hint `{{...}}` do CodeMirror).
- Textos de UI em inglês no código-fonte (regra do AGENTS.md).

## B.9 Testes

- `QueryCompiler`: tabela de casos consulta → SQL/params esperados; tentativas de injeção em
  coluna, alias, `dir`, operador e valor; `LIMIT` sempre presente; `base_filter` sempre presente.
- `DatasetBlock.bind`: coluna não exposta, papel errado, `groupBy` inconsistente, `require_filter`.
- `FlowValidator`/`TemplateScope` com nó configurável: portas `param` ligadas, saídas no escopo.
- Ponta a ponta (`ReportsEndToEndDbTest`, Postgres descartável) sobre `gold.reports_test_area`:
  dataset, liberação, Flow com filtro e com agregado, preview, execução auditada.

## B.10 Questões em aberto

| # | Questão | Proposta inicial |
|---|---|---|
| QB1 | Join entre datasets | não; o agent app da fonte publica a view analítica. Rever depois de uso real |
| QB2 | Views entram no catálogo? `register_gold_table` registra views? | conferir na python-lib; se não, abrir issue lá |
| QB3 | OU nos filtros | depois; `in` primeiro |
| QB4 | Dataset por organização/projeto | depende do host expor organização (ROADMAP §1) |
| QB5 | Usuário comum pode criar datasets? | não; só ADMIN/SCICROP |
| QB6 | Teto do `EXPLAIN` | medir nas bases do CAR e do BR Geo antes de fixar `max-plan-cost` |
| QB7 | Exportar `rows` (CSV) da execução | fora; o produto é o laudo |
| QB8 | Mudança de revisão quebra Flows publicados | a execução valida a consulta contra a revisão atual; se uma coluna saiu, falha em VALIDATING com o nó e a coluna. O editor avisa quais Reports usam o dataset antes de salvar |

## B.11 Plano de implementação

Uma etapa, um PR neste repo (base `main`), cada uma com `mvn -q test` e `./scripts/build-all.sh`
limpos. Docs (`BLOCKS.md`, ARCHITECTURE §47, `AGENTS.md`) atualizados no PR da etapa que muda o
que eles descrevem.

| Etapa | Entrega | Pronto quando |
|---|---|---|
| **B1** Modelo e cadastro | `apps.reports_dataset`, repositório, `DatasetService` com validação contra o catálogo (colunas, tipos, `indexed` × `pg_indexes`), API `/api/datasets` | via `curl`, criar um dataset sobre `gold.reports_test_area`; coluna inexistente ou tipo errado é recusado; editar incrementa `revision` |
| **B2** Compilador | `QueryCompiler` puro + `QuerySpec` (records), allowlist de operadores e agregados | testes de casos e de injeção passam; nenhum caminho gera SQL sem `LIMIT` ou com identificador fora da allowlist |
| **B3** Engine | `BlockProvider`, `DatasetBlockProvider`, `ConfigurableBlock`, `FlowNode.config`, `NodeBlocks.resolve` no validador, no escopo do template e no `problemsFor` | `ds-<slug>` aparece em `GET /api/blocks` com diagnóstico; libera e revoga pela API de hoje; Flow com nó de dataset valida, e as portas aparecem em `/variables` |
| **B4** Execução e guardas | `DatasetBlock.execute` via `BlockQueries`, `limit + 1`, `require_filter`, `EXPLAIN` no preview, `reports_execution_block.details` | teste de ponta a ponta roda filtro, agregado e filtro espacial; consulta sem filtro indexado é recusada; timeout corta; auditoria gravada |
| **B5** API de edição e UI | `resolve`, `preview`, tela Admin → Datasets, construtor no card do nó, hint de variáveis | um admin libera um dataset e um USER monta, pela UI, o exemplo "imóveis do município X com status AT e área > 100 ha" e o imprime no laudo |
| **B6** Datasets reais | com o car-agent-app/br-geo-agent-app: views analíticas no gold registradas no catálogo; datasets `ds-car-imovel-vigente`, `ds-car-feicao-vigente`, `ds-ibama-embargo` | os três exemplos do B.1 rodam num IS de teste |

---

# Parte 2 — Prazo de atualização dos dados

## 2.1 O que existe e o que falta

**Existe:**

- `DataRequirement.maxStaleness` (`Duration`, null = qualquer idade) e `freshWithin(...)` para
  declarar no código.
- `BlockAvailabilityService.check` compara `CatalogTable.lastUpdate` (o da fonte ativa mais
  recente) com o relógio: mais velho que o limite → `STALE`; data ausente ou ilegível → `STALE`
  ("no known last update").
- `parse()` aceita ISO-8601 com ou sem offset; **sem offset, assume UTC**.
- `RequirementCheck` já carrega `sourceId` e `lastUpdate`, e o diagnóstico vai para
  `reports_block_release.availability` na liberação.

**Falta:**

| Lacuna | Hoje |
|---|---|
| Ninguém declara prazo | nenhum Block usa `freshWithin`; tudo é liberado sem limite |
| O admin não define o prazo | o limite só existe em código, e mudar exige release do app |
| Só existe "bloquear" | `BlockService` exige `AVAILABLE` para liberar e para executar; `STALE` bloqueia sempre |
| A data do dado não chega ao laudo | o template não tem como imprimir "Dados do CAR atualizados em …" |
| A execução não guarda a data do dado | `reports_execution` grava versões dos Blocks, não a idade dos dados usados; um PDF regenerado depois não sabe de quando eram os dados |
| A data do catálogo mistura sentidos | para `agente-is`, é o que o app registrou; para outras fontes, é o que o host registrou (2.4) |

## 2.2 Onde o prazo é definido

| Opção | Prós | Contras |
|---|---|---|
| Só no código (`freshWithin`) | versionado com o Block, revisado em PR | o admin do ambiente não ajusta; ambientes diferentes têm ritmos de carga diferentes |
| Só na liberação (admin) | quem conhece o ambiente decide; muda sem release | o autor do Block não consegue dizer "isto não faz sentido com dado de mais de 1 ano" |
| **Os dois: código sugere, admin decide** | default sensato + ajuste local, auditado | um pouco mais de modelo |

**Recomendação: os dois.** O `maxStaleness` do código é o **default sugerido** pelo autor do Block.
Na liberação, o admin confirma, muda ou remove o prazo, por requisito (tabela), e escolhe a
política. A decisão fica gravada **na liberação**, que já é o registro de "quem autorizou o quê com
quais dados". Sem override, vale o do código; sem nenhum dos dois, não há prazo (como hoje).

Gravação: uma coluna nova em `apps.reports_block_release`:

```sql
ALTER TABLE apps.reports_block_release ADD COLUMN IF NOT EXISTS freshness JSONB;
```

```json
{
  "policy": "WARN",
  "requirements": {
    "gold.car_agent_app_imovel_versao": {"maxAge": "P45D"},
    "gold.car_agent_app_feicao":        {"maxAge": "P45D"},
    "gold.br_geo_agent_app_inpe_deter_alert": {"maxAge": "P10D", "policy": "BLOCK"}
  }
}
```

`maxAge` em ISO-8601 (`Duration.parse`), `null` explícito = sem prazo mesmo que o código sugira.
Uma tabela própria não se paga: o prazo vive e morre com a liberação. Revogar apaga os dois, e
liberar de novo pede o prazo outra vez, pré-preenchido com a sugestão do código (o prazo anterior
fica no log da revogação). Dataset Blocks (parte B) usam o mesmo campo.

## 2.3 Política por Block: BLOCK × WARN

| Política | Dado vencido ou de data desconhecida |
|---|---|
| `BLOCK` | Block **não utilizável**: some para o USER, a liberação é recusada, a execução falha em VALIDATING (comportamento de hoje para `STALE`) |
| `WARN` | Block **utilizável**; a execução termina `COMPLETED` com **avisos** gravados, o chip aparece na UI e o template pode imprimir a data |

**Recomendação: `WARN` como default.** Num laudo, dado de 60 dias com a data impressa é melhor do
que laudo nenhum, e quem assina decide. `BLOCK` fica para requisitos em que dado velho torna o
resultado errado, não só antigo (ex.: alertas DETER "dos últimos 30 dias"); o autor do Block pode
sugerir `BLOCK` no código (`freshWithin(maxAge, BLOCK)`), e o admin confirma.

Mudanças que isso traz:

- `AvailabilityStatus` continua `AVAILABLE`/`STALE`/`MISSING_DATA`. O que muda é a regra de uso no
  `BlockService`: `usable = released && (status == AVAILABLE || (status == STALE && policy == WARN))`.
  `MISSING_DATA` bloqueia sempre.
- Liberar um Block `STALE` passa a ser possível com política `WARN` (o admin vê o aviso e confirma).
- `problemsFor` passa a devolver problemas **e** avisos; os avisos vão para um campo novo
  `reports_execution.warnings JSONB` e não mudam o status.
- O `BlockAvailabilityService` recebe o prazo efetivo (override da liberação → código → nenhum),
  em vez de ler só `requirement.maxStaleness()`.

## 2.4 O que `lastUpdate` significa

| Fonte (`sourceType`) | Quem grava `lastUpdate` | O que significa |
|---|---|---|
| `agente-is` (car-agent-app, br-geo-agent-app…) | o agent app, ao chamar `register_gold_table` da python-lib | quando o app **publicou** a tabela pela última vez. O host não calcula nada |
| `database` / `csv` / demais | o host, no fluxo de ingestão do Catalog Explorer | quando o host carregou ou sincronizou a tabela |
| fallback `information_schema` (dev local) | ninguém | sempre desconhecida |

Nos dois casos é a **data da carga**, não a **data de referência** da fonte. O car-agent-app pode
ter rodado ontem sobre um dump do SICAR de dois meses. Para o laudo, o que importa é a segunda. A
proposta é pedir à python-lib um campo opcional `referenceDate` (data de referência do dado, além da
data da carga) no registro do gold, devolvido pelo `GET .../catalog/tables`. Até lá, o laudo
imprime a data da carga e chama assim ("dados carregados em").

Regras do lado do REPORTS:

- **Granularidade:** usa o `lastUpdate` da tabela. Se vier vazio e as colunas exigidas tiverem
  `lastUpdate`, usa a **mais antiga** delas (conservador).
- **Desconhecida:** com prazo definido, conta como vencida (`STALE` com motivo "data desconhecida");
  sob `WARN` o Block segue utilizável e o laudo imprime "data de atualização não informada". Sem
  prazo, não gera aviso, mas a execução grava `unknown`.
- **Fuso:** compara sempre `Instant`. Texto sem offset é UTC (como o `parse()` já faz); conferir na
  python-lib e no back qual formato cada um grava e documentar no ARCHITECTURE §47.4. Exibição em
  `America/Sao_Paulo`.
- **Datas no futuro** (relógio de quem registrou adiantado): tratadas como "agora", com aviso no
  diagnóstico do admin.
- **Cache:** o snapshot do catálogo tem 60 s de cache; para prazos em dias isso é irrelevante, e a
  liberação já força `refresh()`.

## 2.5 Registro na execução e uso no template

**Por que gravar.** O PDF é regenerado sob demanda a partir do contexto gravado (D7). Se a data do
dado fosse lida na hora de regenerar, um laudo de março sairia em junho com a data de junho. A data
precisa ser gravada **na execução**.

- **Por nó**, em `reports_execution_block.details` (o mesmo campo JSONB da parte B):
  `{"data": [{"table": "gold.car_agent_app_feicao", "sourceId": "...", "sourceType": "agente-is",
  "lastUpdate": "2026-09-30T03:12:00Z", "referenceDate": null, "maxAge": "P45D", "policy": "WARN",
  "status": "AVAILABLE"}]}`.
- **No contexto da execução**, dentro do namespace reservado `report`, para o template e para a
  regeneração:

| Variável | Tipo | Conteúdo |
|---|---|---|
| `report.data.<nó>.updatedAt` | string | `dd/mm/aaaa` da data mais antiga entre as tabelas do nó, ou vazio se desconhecida |
| `report.data.<nó>.updatedAtIso` | datetime | a mesma, ISO-8601 |
| `report.data.<nó>.stale` | boolean | algum requisito do nó estava vencido (sob `WARN`) |
| `report.sources` | array | uma linha por tabela usada, sem repetição: `{table, sourceName, updatedAt, updatedAtIso, referenceDate, stale}` |
| `report.dataWarnings` | array | os avisos de prazo da execução, em texto |

`report` já é reservado (nenhum nó usa esse id) e `report.data` é OBJECT, então o
`TemplateRenderer.checkPath` aceita os campos internos sem mudança. O `TemplateScope` declara
`data`, `sources` e `dataWarnings` no namespace `report`. Exemplo no laudo:

```html
<p class="fonte">Dados do CAR atualizados em {{report.data.imovel.updatedAt}}.</p>
<ul>{{#report.sources}}<li>{{sourceName}} ({{table}}): {{updatedAt}}</li>{{/report.sources}}</ul>
```

`ExecutionContext.reportMetadata(...)` passa a receber esses dados, e a execução os grava junto do
contexto, para que preview, execução e regeneração usem o mesmo valor.

**Reexecução** (Fase 7): além de avisar quando a versão de um Block mudou, compara
`report.sources` da original com a nova e avisa "os dados de X mudaram desde a execução original
(30/09 → 20/10)".

## 2.6 UI

- **Diálogo de liberação** (hoje é um botão): passa a abrir um diálogo com, por requisito, a tabela,
  a fonte, a data atual do dado, o prazo sugerido pelo código e um campo de prazo em dias (vazio =
  sem prazo), e a política `WARN`/`BLOCK`. O botão "Release" mostra o resultado com o prazo
  escolhido antes de confirmar.
- **Chips** na lista de Blocks (admin e USER) e no card do nó: "Dados de 30/09/2026" (neutro),
  "Desatualizado: limite 45 d" (amarelo, `WARN`), "Bloqueado: dados vencidos" (vermelho, só admin),
  "Data desconhecida" (cinza).
- **Execução:** avisos de prazo no topo do resultado e no histórico, ao lado do status.
- **Template:** as variáveis `report.data.*` e `report.sources` entram no hint `{{...}}`.

## 2.7 Plano de implementação

| Etapa | Entrega | Pronto quando |
|---|---|---|
| **F1** Prazo efetivo e política | `DataRequirement` ganha política sugerida; `freshness JSONB` em `reports_block_release`; `BlockAvailabilityService` recebe o prazo efetivo; regra de uso com `WARN`; granularidade por coluna; data no futuro | testes com `Clock` fixo: código sem override, override maior/menor/`null`, `WARN` utilizável e `BLOCK` não, data desconhecida, sem offset = UTC |
| **F2** Liberação com prazo | `POST /api/blocks/{id}/release` aceita `{freshness}`; validação (`Duration` positiva, tabelas do Block); diálogo e chips na UI | via `curl` e pela tela, liberar um Block `STALE` com `WARN` funciona e com `BLOCK` é recusado; o USER vê o chip |
| **F3** Gravação na execução | `reports_execution_block.details.data`, `reports_execution.warnings`; `problemsFor` devolve problemas e avisos | execução com dado vencido sob `WARN` termina `COMPLETED` com aviso gravado; sob `BLOCK` falha em VALIDATING com o motivo |
| **F4** Template | `report.data`, `report.sources`, `report.dataWarnings` no contexto gravado e no `TemplateScope` | um template imprime "Dados do CAR atualizados em dd/mm/aaaa"; o PDF regenerado dias depois imprime a mesma data |
| **F5** Reexecução e fontes | aviso de dados mudados na reexecução; pedido de `referenceDate` na python-lib e no back (PRs próprios); prazos sugeridos nos Blocks do CAR/BR Geo | reexecução mostra a mudança de data; ARCHITECTURE §47.4 e BLOCKS.md descrevem prazo, política e as variáveis |

F1 a F4 não dependem da parte B; se B vier antes, o campo `details` nasce em B4 e F3 só o usa.

## 2.8 Questões em aberto

| # | Questão | Proposta inicial |
|---|---|---|
| QF1 | Prazo em dias corridos ou "desde a última carga esperada" (agenda da fonte) | dias corridos; agenda é do agent app |
| QF2 | Calcular a data a partir do próprio dado (`max(dat_atualizacao)`) | não por padrão: custa uma consulta por requisito; talvez como opção do requisito no código |
| QF3 | Notificar o admin quando um Block liberado vence | depois; o host não tem canal de notificação para plugins |
| QF4 | `referenceDate` na python-lib | issue na python-lib e no back, antes de F5 |
