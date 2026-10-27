# REPORTS — ROADMAP

Plano de implementação do REPORTS como Agent App do InfiniteStack. Parte de
[MASTER.md](MASTER.md) (o quê) e [ARCHITECTURE.md](ARCHITECTURE.md) (como), e respeita o contrato
de Agent Apps em [../README.md](../README.md), [../design.md](../design.md) e [../AGENTS.md](../AGENTS.md).

> **Decisão sobre os Blocks.** Os Blocks listados no MASTER (§7, §21) e no ARCHITECTURE (§44)
> — `GET_PROPERTY`, `GET_GEOMETRY`, `GET_CENTROID`, `GET_MUNICIPALITY`, `CALCULATE_AREA` — **não
> são implementados agora**. Eles serão discutidos um a um na Fase 8. Antes disso o roadmap constrói
> a engine e o mecanismo que decide quando um Block pode ser usado:
>
> 1. cada Block declara **de quais dados do catálogo** ele depende;
> 2. o app **verifica no catálogo** se esses dados existem (e estão atualizados);
> 3. havendo os dados, um administrador **libera** o Block;
> 4. a partir daí qualquer usuário monta a consulta que quiser com aquele Block.

> **Estado (2026-10-26):** fases 0 a 7 implementadas em `reports/` (versão 0.1.0, ainda não
> instalada num IS real). Fase 8 em andamento: 13 Blocks (CAR, BR Geo e Satellite Hub,
> [BLOCKS.md](BLOCKS.md)), o editor visual de Flow e 4 relatórios prontos
> ([READY-MADE-REPORTS.md](READY-MADE-REPORTS.md)), testados com dados reais do banco de dev. O que foi
> construído e onde difere do desenho está na seção 47 do [ARCHITECTURE.md](ARCHITECTURE.md).

---

## 1. Ponto de partida (o que já existe no IS)

Levantamento feito no código em 2026-10-26. Ele muda algumas premissas dos documentos:

| Tema | Situação real | Impacto no REPORTS |
|---|---|---|
| **Catálogo de dados** | É o *Catalog Explorer* do back: um JSON (`/etc/data/catalogExplorer.json`) lido por `JsonCatalogExplorerRepository`. Estrutura source → schema → table → column, com `status`, `last_update`, tipo de coluna e `geometry`. Os dados físicos ficam nos schemas `bronze`/`silver`/`gold` do banco de destino. | É contra ele que a disponibilidade dos Blocks é verificada. |
| **Leitura do catálogo por um plugin** | **Não existe porta interna de leitura.** O plugin só tem `POST /api/extensions/internal/catalog/gold`, que serve para registrar tabelas (`X-IS-Plugin-Id` + `X-IS-Plugin-Internal-Token`). | É preciso criar `GET` interno no back (Fase 3). Em dev local, fallback em `information_schema`. |
| **Catálogo da IS Store** (`/api/v1/catalog`) | É o catálogo de agentes e apps do hub, não de dados. | Não usar. |
| **Identidade do usuário** | O host injeta `X-IS-User-Id`, `X-IS-Username`, `X-IS-Role` (ADMIN/SCICROP/USER/SERVICE) e `X-IS-Principal-Type`. Não existe header de organização. | Liberar Blocks fica restrito a ADMIN/SCICROP. Por enquanto não há permissão por organização ou projeto. |
| **ACL por dataset** | Não existe. O host decide só quem abre o app. | O MASTER §32 ("Organization / Project / Data Source") fica para depois. No MVP o controle é feito por papel e pela liberação do Block. |
| **GEOLIB** | **Não existe no código.** O que mais se aproxima são as tabelas `gold.br_geo_agent_app_*` (IBGE município/bioma, embargos, DETER, PRODES, ZARC, SRID 4674), do `apps/br-geo-agent-app`, e `gold.car_agent_app_*` (imóveis do CAR e suas geometrias), do `apps/car-agent-app`. | Os Blocks consomem essas tabelas gold via catálogo, não um "GEOLIB". |
| **Stack do app** | Spring Boot 3.3 / Java 17, Thymeleaf, assets sem CDN, tabelas em `apps.<plugin_id>_*`. | As interfaces TypeScript do ARCHITECTURE são conceituais. A implementação é em Java. |

---

## 2. Princípios de execução

- **Ordem do ARCHITECTURE §43**, com o gate de catálogo inserido antes dos Blocks reais:
  modelo → engine → catálogo/liberação → template → preview → PDF → UI → versionamento → Blocks.
- **Fluxo back→front do AGENTS.md** dentro de cada fase: persistência → DTO → service → controller →
  `curl` → tela.
- **Uma fase, um PR** neste repo (branch de `main`, PR contra `main`). Mudanças no back vão num PR
  próprio do `infinitestack-back` (base `dev`).
- **Sem editor visual de grafo** antes de a engine funcionar (ARCHITECTURE §43). O Flow é editado
  como lista ordenada de nós com mapeamento de inputs.
- Cada fase termina com `mvn -q test` e `./scripts/build-all.sh` limpos e com o `.ispz` instalando
  num IS local.

---

## 3. Fases

### Fase 0 — Fundação do app

**Objetivo:** um `.ispz` vazio do REPORTS que instala, abre no iframe e responde health.

- Definir `plugin_id` (proposta: `reports`, com tabelas `apps.reports_*`) e o pacote Java
  (proposta: `com.infinitestack.reports`).
- Definir o layout do repo. Proposta: o app em `reports/` (ou `agent-app/`, como no car/br-geo),
  com `recomendador-manejo/` mantido só como exemplo de referência, e ajustar `AGENTS.md`/`README.md`
  para descrever este repo como o repo do REPORTS.
- Scaffold conforme o contrato: manifest, `PluginRuntimeController`, `AgentPanelController`,
  `isp-index`/`isp-status`, link de volta para `/agents/apps`, `PluginDataSourceConfig`,
  `AppSchema` + `CREATE SCHEMA IF NOT EXISTS apps`, scripts step1–3.
- Filtro/interceptor que lê os headers `X-IS-*` e expõe um `CurrentUser` (id, username, role).

**Pronto quando:** o app aparece em Agents → Apps, `runtime-health` responde `ok` e
`GET /api/me` devolve o usuário vindo dos headers.

### Fase 1 — Modelo de domínio e persistência

**Objetivo:** guardar as definições e o histórico, sem executar nada ainda.

Tabelas (JSON em `jsonb`; todas com prefixo `reports_`):

| Tabela | Conteúdo |
|---|---|
| `apps.reports_report` | id, nome, descrição, dono (`X-IS-User-Id`), datas, versão publicada atual |
| `apps.reports_report_version` | report, nº da versão, `inputs_schema`, `flow_json`, `template_html`, `template_css`, status `DRAFT`/`PUBLISHED`. Uma versão publicada é imutável. |
| `apps.reports_execution` | report, versão, inputs, status (ARCHITECTURE §24), erro, duração, usuário, versões de cada Block usado |
| `apps.reports_execution_block` | execução, nó, block id + versão, inputs/outputs (com máscara), status, tempos, erro |
| `apps.reports_block_release` | block id, versão, liberado por, quando, estado da verificação de dados no momento da liberação |

- Neste começo, Flow e Template ficam **dentro** da versão do Report. A separação em
  `flows`/`templates` próprios, reaproveitáveis (ARCHITECTURE §34–35), fica para depois do MVP.
  Assim a versão continua sendo um snapshot reproduzível sem precisar de joins entre versões.
- CRUD de Report e de versão via API (`/api/reports`, `/api/reports/{id}/versions`), seguindo a
  separação Definition × Execution × Result do ARCHITECTURE §39.

**Pronto quando:** criar, editar e publicar uma versão via `curl`, com testes de repositório.

### Fase 2 — Engine (Block Registry, Context, Flow Engine)

**Objetivo:** executar um Flow com Blocks de teste, sem tocar em dados reais.

- Contratos Java: `ReportBlock` (`definition()`, `execute(input, context)`), `BlockDefinition`
  (id, nome, descrição, versão, ports), `BlockPort` (nome, `DataType`, obrigatório), enum
  `DataType` com os tipos iniciais (§12).
- `BlockRegistry`: os Blocks são beans Spring descobertos automaticamente, e assim um Block novo
  não altera o núcleo (§2.6).
- `ExecutionContext` com `inputs`, `values` por namespace (= id do nó) e `metadata`.
- Validação do Flow: ids únicos, Blocks existentes, conexões `nó.port → nó.port` válidas, tipos
  compatíveis, inputs obrigatórios cobertos, **sem ciclos** (ordenação topológica).
- Executor: ordem topológica, timeout por Block (default 30 s, §27), limites configuráveis (§28),
  registro em `reports_execution_block`, ciclo de estados do §23 e erro com report/versão/execução/
  Block/input (§26).
- Execução **síncrona** (§30). O modelo já guarda status para o worker assíncrono futuro.
- Blocks **só de teste**, em `src/test` (ex.: constante, soma, eco). Não são Blocks de produto.

**Pronto quando:** testes cobrem um Flow linear, um Flow ramificado, um ciclo rejeitado, um tipo
incompatível, um timeout e uma falha no meio, e `POST /api/reports/{id}/execute` retorna o Context.

### Fase 3 — Catálogo e liberação de Blocks

**Objetivo:** o mecanismo central desta proposta. Um Block só aparece para o usuário se os dados
dele existem no catálogo e se ele foi liberado.

1. **Requisito de dados no contrato do Block:** `BlockDefinition.dataRequirements`, uma lista de
   `{ source?, schema, table, columns[], maxStaleness? }`. Um Block puramente de cálculo (ex.:
   área de uma geometria recebida) não declara nada e depende só da liberação.
2. **Porta interna de leitura no back** (PR no `infinitestack-back`):
   `GET /api/extensions/internal/catalog`, autenticada como as outras portas internas, que devolve
   sources/schemas/tabelas/colunas com `status` e `last_update`. Atualizar o `ARCHITECTURE.md` do
   back e a doc das portas internas no mesmo PR.
3. **`CatalogClient`** no app (`IS_HOST_BASE_URL` + `IS_PLUGIN_INTERNAL_TOKEN`), com fallback em
   `information_schema` quando rodando fora do host (dev local).
4. **`BlockAvailabilityService`:** calcula `AVAILABLE`, `MISSING_DATA` (lista o que falta) ou
   `STALE`. Recalcula sob demanda e com cache curto.
5. **Liberação:** `POST/DELETE /api/blocks/{id}/release`, só para ADMIN/SCICROP. Só é possível
   liberar um Block `AVAILABLE`.
   - `GET /api/blocks` devolve ao usuário comum apenas os Blocks liberados e disponíveis.
   - O admin vê todos, com o diagnóstico.
6. **Revalidação na execução:** se os dados de um Block liberado sumirem, a execução falha com
   erro claro ("Block X sem dados: tabela Y ausente") em vez de rodar pela metade.
7. Tela de administração "Blocks": lista, estado dos dados, botão liberar/revogar.

**Pronto quando:** com um Block de teste que exige uma tabela, o Block aparece como
`MISSING_DATA` sem a tabela e `AVAILABLE` depois de criá-la e registrá-la no catálogo; depois de
liberado, aparece para um USER, e ao revogar some.

### Fase 4 — Template Engine e Preview

**Objetivo:** Context + HTML → HTML renderizado e seguro.

- Engine *logic-less* com sintaxe `{{namespace.campo}}` (§13). Candidatas: JMustache ou
  Handlebars.java. A decisão sai num spike curto, avaliando o modo estrito e os helpers controlados.
- **Modo estrito:** uma variável inexistente no Context é erro de validação, com a lista das
  variáveis inválidas (MASTER §13).
- Escape de HTML por padrão e sanitização do HTML final com allowlist (sem `<script>`, `on*`,
  `iframe` nem URLs externas), conforme MASTER §19 e ARCHITECTURE §16–17.
- Lista das variáveis disponíveis, derivada dos outputs do Flow, para ajudar quem escreve o template.
- `POST /api/reports/{id}/preview` (executa e renderiza, sem persistir resultado) e preview de
  uma execução já feita.
- No front, o preview roda em `<iframe sandbox srcdoc>` sem `allow-scripts`.

**Pronto quando:** testes de render, de variável inválida e de sanitização passam, e o preview
aparece na UI.

### Fase 5 — PDF

**Objetivo:** HTML renderizado → PDF.

- **Decisão do renderer** (ARCHITECTURE §18 pede headless browser):
  - **openhtmltopdf**: Java puro, sem dependência no host, suporta `@page`, quebras, header/footer
    e margens, mas o CSS é limitado (sem flex/grid). É a proposta para o MVP.
  - **Chromium headless**: CSS moderno, mas exige binário no servidor do IS, que o `.ispz` não
    controla hoje.
  - Isolar atrás de `PdfRenderer` permite trocar depois sem mexer no resto (§41).
- Tamanho de página, orientação e margens configuráveis na versão do Report.
- Imagens só vindas do Context (data URI). Nada de URL remota (§20).
- Armazenamento: gerar sob demanda a partir da execução, que é reproduzível, ou gravar em
  `IS_PLUGIN_INSTALL_PATH/data`. Decidir considerando o volume do host.
- `GET /api/executions/{id}/result?format=pdf|html`.

**Pronto quando:** um template com tabela, quebra de página e header/footer gera um PDF correto, e
os limites de tamanho são respeitados.

### Fase 6 — UI do MVP

**Objetivo:** o fluxo do MASTER §24 de ponta a ponta na tela, seguindo o design.md (tema escuro,
header + sidebar, pt-BR, assets locais).

- Lista de Reports → editor com abas **Inputs**, **Flow**, **Template**, **Preview**.
- Flow como lista de nós: escolher um Block (só os liberados), mapear cada input para um input do
  Report, um output de outro nó ou um valor fixo, e validar.
- **Stack (D9):** Thymeleaf só como casca e JavaScript puro em `static/app/*.js` (`api.js`,
  `reports.js`, `flow-editor.js`, `template-editor.js`), sem framework SPA e sem Bootstrap. O CSS sai
  dos tokens e componentes do `design.md`. É o padrão do `satellite-hub` e do
  `chatbot-workflow-manager`. React/Vue exigiriam npm e bundler no build do `.ispz`. Se o estado do
  editor apertar, a saída é Alpine.js vendorado.
- Editor HTML/CSS: **CodeMirror 5 vendorado desde o início**, reaproveitando o de
  `infinitestack-agent-apps/dbviewer/src/main/resources/static/vendor/codemirror/`, mais os modos
  `xml`/`css`/`htmlmixed` e um hint com as variáveis `{{...}}` disponíveis.
- **O HTML do template nunca passa pelo Thymeleaf.** Ele trafega só como JSON pela API, porque o
  Thymeleaf interpretaria expressões inline dentro dele. Pelo mesmo motivo, o JS grande fica em
  arquivo próprio, fora do template.
- Execução: formulário gerado a partir do `inputs_schema`, outputs por nó, preview e download do PDF.
- Histórico de execuções com status e erro por Block.

**Pronto quando:** um usuário faz os 11 passos do MASTER §24 só pela UI, com um Block liberado.

### Fase 7 — Versionamento e reprodutibilidade

- Publicar uma versão a congela. Editar sempre cria um rascunho novo.
- A execução registra `reportVersion` e a versão de cada Block (§36).
- Reexecutar uma execução antiga com a mesma versão e os mesmos inputs.
- Comparar duas versões (diff textual de Flow e Template).
- Política para Block cuja versão mudou: a execução nova usa a versão atual liberada e a antiga
  continua registrada. Discutir se vale manter versões antigas de Blocks executáveis.

### Fase 8 — Blocks (fase de discussão)

**Esta fase começa com conversa, não com código.** Para cada Block candidato:

1. definir o contrato (inputs/outputs/tipos);
2. definir os `dataRequirements` (quais tabelas/colunas do catálogo);
3. verificar no catálogo do ambiente se os dados existem;
4. implementar, testar e **liberar** pela tela de administração.

Candidatos e as fontes que existem hoje, como ponto de partida da discussão (nada decidido):

| Candidato | Fonte provável hoje |
|---|---|
| Imóvel por id / código CAR | `gold.car_agent_app_imovel`, `_imovel_geometria` |
| Município de um ponto | `gold.br_geo_agent_app_ibge_municipality` (SRID 4674) |
| Centroide / área de geometria | cálculo (PostGIS no banco de destino), sem dado próprio |
| Embargos, DETER, PRODES, ZARC na área | `gold.br_geo_agent_app_*` |

**Estilo A primeiro.** A Fase 8 faz só Blocks de contrato fixo, escritos em código (os do CAR para
o laudo socioambiental, catalogados em [BLOCKS.md](BLOCKS.md)). Os Dataset Blocks (estilo B,
consulta livre sobre uma base liberada) e o prazo de atualização dos dados estão desenhados em
[BLOCKS-FUTURO.md](BLOCKS-FUTURO.md) e entram depois.

**Feito na Fase 8 (2026-10-26):**

- Pool somente leitura para Blocks (`blockDataSource` + `BlockQueries`) e namespace `{{report.*}}`.
- Blocks do CAR: `car-property`, `car-property-geometry`, `car-environmental-layers`, `car-overlaps`,
  `car-history`, `car-property-map` (PNG gerado no app, sem tiles).
- Relatórios prontos (catálogo, API, UI "Use") e o laudo socioambiental do imóvel CAR, validados
  contra os Blocks reais em teste. Contra o dev: ~0,5 s por laudo e PDF em menos de 1 s.

**Feito na Fase 8, segunda parte (2026-10-26):** os 6 Blocks do BR Geo (municípios, biomas, embargos
IBAMA, DETER, PRODES, PAM) e o do Satellite Hub (série de índice de vegetação), documentação
detalhada de todo Block (`BlockDocs`), editor visual de Flow (ARCHITECTURE §47.7) e três prontos
novos: laudo completo, diagnóstico de área e monitoramento de vegetação. Integrações desenhadas em
[INTEGRATIONS.md](INTEGRATIONS.md): pedir processamento ao Satellite Hub e script Python do Skills Lab
como Block.

**Pendente na Fase 8:** formatação pt-BR de números nos templates; área de Hidrografia nula no SICAR;
Blocks de ZARC e CONAB (as tabelas já estão no BR Geo).

### Fase 9 — Endurecimento e release 1.0.0

- Limites e timeouts revisados, logs com `reportId`/`reportVersion`/`executionId`/`blockId` (§38).
- Métricas de execução (§37) na tela de histórico.
- Atualizar `AGENTS.md`/`ARCHITECTURE.md` deste repo com o que foi construído.
- Versão nos três lugares, `.ispz` gerado **a partir da `main`**, instalação num IS real.

### Depois da Fase 8 — Dataset Blocks e prazo dos dados (planejado)

Desenho em [BLOCKS-FUTURO.md](BLOCKS-FUTURO.md), com etapas e critérios de pronto:

- **Dataset Blocks (B1–B6):** o admin libera uma tabela do catálogo (`apps.reports_dataset`, colunas
  expostas com papéis) e ela vira o Block `ds-<slug>`; o usuário monta a consulta no nó do Flow
  (filtros, agrupamento, agregados, filtro espacial), compilada pelo app em SQL com allowlist,
  parâmetros, `LIMIT` e timeout.
- **Prazo de atualização (F1–F5):** prazo sugerido no código e decidido pelo admin na liberação,
  política `WARN` (default) ou `BLOCK`, data dos dados gravada na execução e impressa no laudo
  (`{{report.data.<nó>.updatedAt}}`).

### Depois do MVP

Editor visual do Flow, adaptando o `flow-builder.js` do `chatbot-workflow-manager` (SVG puro,
segunda visão do mesmo JSON, com `ui:{x,y}` opcional por nó), worker assíncrono e fila (§30–31), cache por versão + parâmetros (§29), Flows/Templates
reaproveitáveis (§34–35), Blocks de mapa e imagem (§19–20), Blocks lógicos (IF/FILTER/LOOP…),
permissão por organização/projeto quando o host expuser isso, saídas JSON/dashboard (§46),
agendamento e distribuição (MASTER §22).

---

## 4. Decisões em aberto

| # | Decisão | Proposta | Quando |
|---|---|---|---|
| D1 | `plugin_id` e layout do repo | `reports`, app em `reports/`; o `recomendador-manejo` de exemplo saiu do repo em 2026-10-26 | **decidido** |
| D2 | Leitura do catálogo | porta interna `GET` no back (back#1279) + fallback `information_schema` | **decidido** |
| D3 | Quem libera Blocks | ADMIN/SCICROP via `X-IS-Role` | **decidido** |
| D4 | Liberação global ou por usuário/grupo | global no MVP (não há header de org) | **decidido** |
| D5 | Engine de template | JMustache (modo estrito, sem partials) | **decidido** |
| D6 | Renderer de PDF | openhtmltopdf 1.1.22, atrás de `PdfRenderer` | **decidido** |
| D7 | Guardar PDF ou regenerar | regenerar sob demanda a partir do contexto gravado | **decidido** |
| D8 | Conjunto de Blocks do MVP | discussão na Fase 8 | Fase 8 |
| D9 | Stack da UI | Thymeleaf como casca + JS puro em `static/app/`, CodeMirror 5 vendorado, preview em iframe sandbox | **decidido** (2026-10-26) |
| D10 | Estilo dos Blocks | os dois: A (contrato fixo) na Fase 8; B (Dataset Blocks) depois, em [BLOCKS-FUTURO.md](BLOCKS-FUTURO.md) | **decidido** (2026-10-26) |
