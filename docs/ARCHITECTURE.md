# REPORTS — ARCHITECTURE

## 1. Objetivo

Este documento define a arquitetura técnica do aplicativo REPORTS dentro do InfiniteStack.

O objetivo é estabelecer uma arquitetura modular, extensível e segura para:

- execução de Blocks;
- composição de Flows;
- gerenciamento de Context;
- renderização de Templates;
- geração de HTML;
- geração de PDF;
- versionamento;
- auditoria.

---

# 2. Princípios Arquiteturais

O REPORTS deve seguir os seguintes princípios:

### 2.1 Separation of Concerns

Cada camada possui uma responsabilidade clara.

```text
Block
Flow
Execution
Template
Renderer
Output
```

não devem estar acoplados diretamente.

---

### 2.2 Reutilização

Blocks não pertencem a um Report específico.

Um Block deve poder ser utilizado em múltiplos Reports.

---

### 2.3 Determinismo

Sempre que possível, a mesma entrada e a mesma versão de um Block devem produzir o mesmo resultado.

---

### 2.4 Versionamento

Uma Execution deve ser vinculada às versões exatas utilizadas.

---

### 2.5 Segurança

O Template nunca deve possuir acesso direto aos recursos internos.

---

### 2.6 Extensibilidade

Novos Blocks devem poder ser adicionados sem alterar o núcleo da engine.

---

# 3. Visão Geral

```text
┌────────────────────────────────────────────────────────────┐
│                         InfiniteStack                       │
│                                                            │
│  ┌──────────────┐       ┌──────────────────────────────┐  │
│  │ Reports UI   │──────►│         Reports API          │  │
│  └──────────────┘       └──────────────┬───────────────┘  │
│                                        │                  │
│                                        ▼                  │
│                            ┌──────────────────────┐       │
│                            │    Report Engine      │       │
│                            └──────────┬───────────┘       │
│                                       │                   │
│                ┌──────────────────────┼────────────────┐  │
│                │                      │                │  │
│                ▼                      ▼                ▼  │
│         ┌─────────────┐       ┌─────────────┐   ┌────────┐
│         │ Flow Engine │       │ Block Engine│   │Template│
│         └──────┬──────┘       └──────┬──────┘   └───┬────┘
│                │                     │              │     │
│                │                     │              ▼     │
│                │                     │        ┌──────────┐│
│                │                     │        │ Renderer ││
│                │                     │        └────┬─────┘│
│                │                     │             │      │
│                ▼                     ▼             ▼      │
│          Execution Context     IS Services        PDF     │
│                                      │                    │
│                        ┌─────────────┴─────────────┐      │
│                        │                           │      │
│                      GEOLIB                       DATA   │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

# 4. Componentes

## 4.1 Reports UI

Responsável por:

- gerenciamento de Reports;
- gerenciamento de Flows;
- seleção de Blocks;
- configuração de Inputs;
- edição de Templates;
- Preview;
- execução;
- download do resultado.

Não deve conter regra de negócio da engine.

---

# 5. Reports API

Responsável por:

- autenticação;
- autorização;
- CRUD;
- validação;
- versionamento;
- execução;
- acesso aos resultados.

Endpoints conceituais:

```text
GET    /reports
POST   /reports
GET    /reports/:id
PUT    /reports/:id
DELETE /reports/:id

POST   /reports/:id/execute

GET    /reports/:id/versions
POST   /reports/:id/versions

GET    /blocks
GET    /blocks/:id

GET    /flows
POST   /flows
GET    /flows/:id
PUT    /flows/:id
```

---

# 6. Report Engine

É o núcleo do aplicativo.

Responsabilidades:

```text
Load Report
    ↓
Resolve Version
    ↓
Validate Flow
    ↓
Create Context
    ↓
Execute Flow
    ↓
Render Template
    ↓
Generate Output
```

---

# 7. Flow Engine

Responsável por interpretar e executar um Flow.

Um Flow pode ser representado como um grafo direcionado.

```text
Node A
  │
  ▼
Node B
  │
  ├────► Node C
  │
  └────► Node D
```

Cada Node referencia um Block.

---

# 8. Flow Model

Exemplo:

```json
{
  "id": "property-report-flow",
  "version": 1,
  "nodes": [
    {
      "id": "property",
      "block": "GET_PROPERTY"
    },
    {
      "id": "geometry",
      "block": "GET_GEOMETRY"
    },
    {
      "id": "centroid",
      "block": "GET_CENTROID"
    },
    {
      "id": "municipality",
      "block": "GET_MUNICIPALITY"
    }
  ],
  "connections": [
    {
      "from": "property.id",
      "to": "geometry.propertyId"
    },
    {
      "from": "geometry.geometry",
      "to": "centroid.geometry"
    },
    {
      "from": "centroid.latitude",
      "to": "municipality.latitude"
    },
    {
      "from": "centroid.longitude",
      "to": "municipality.longitude"
    }
  ]
}
```

---

# 9. Block Engine

O Block Engine mantém um Registry.

Conceito:

```text
BlockRegistry

GET_PROPERTY
GET_GEOMETRY
GET_CENTROID
GET_MUNICIPALITY
CALCULATE_AREA
```

Cada Block possui um Executor.

```text
BlockDefinition
      │
      ▼
BlockExecutor
      │
      ▼
Output
```

---

# 10. Block Interface

Conceitualmente:

```typescript
interface ReportBlock {
    getDefinition(): BlockDefinition;

    execute(
        input: BlockInput,
        context: ExecutionContext
    ): Promise<BlockOutput>;
}
```

O Block não deve conhecer o Template.

O Block não deve conhecer o PDF.

O Block apenas executa sua responsabilidade.

---

# 11. Block Definition

```typescript
interface BlockDefinition {
    id: string;
    name: string;
    description: string;
    version: string;

    inputs: BlockPortDefinition[];
    outputs: BlockPortDefinition[];
}
```

---

# 12. Block Ports

```typescript
interface BlockPortDefinition {
    name: string;
    type: DataType;
    required: boolean;
}
```

Tipos iniciais:

```text
string
number
boolean
date
datetime
object
array
geometry
```

Tipos futuros:

```text
image
table
chart
map
file
```

---

# 13. Execution Context

O Context representa o estado da execução.

Conceito:

```typescript
interface ExecutionContext {
    inputs: Record<string, unknown>;

    values: Record<string, unknown>;

    metadata: {
        reportId: string;
        reportVersion: number;
        executionId: string;
    };
}
```

Exemplo:

```json
{
  "inputs": {
    "propertyId": 123
  },
  "values": {
    "property": {},
    "geometry": {},
    "centroid": {},
    "municipality": {}
  }
}
```

---

# 14. Namespaces

Os valores devem ser organizados por namespace.

Exemplo:

```text
property.name
property.area

geometry.geojson

centroid.latitude
centroid.longitude

municipality.name
municipality.state
municipality.ibgeCode
```

Isso evita colisões.

---

# 15. Template Engine

A Template Engine recebe:

```text
HTML Template
+
Execution Context
```

e produz:

```text
Rendered HTML
```

Exemplo:

```text
Template:

<h1>{{property.name}}</h1>

<p>{{municipality.name}}</p>
```

Context:

```json
{
  "property": {
    "name": "Fazenda São João"
  },
  "municipality": {
    "name": "Garça"
  }
}
```

Resultado:

```html
<h1>Fazenda São João</h1>

<p>Garça</p>
```

---

# 16. Template Security

O renderer deve possuir acesso somente aos dados explicitamente disponibilizados pelo Context.

Não permitir:

```text
filesystem access
network access
database access
environment variables
arbitrary code execution
```

O template é uma camada de apresentação.

---

# 17. HTML Renderer

Responsável por transformar o Template Engine em documento HTML final.

Pipeline:

```text
Template
    ↓
Parse
    ↓
Resolve Variables
    ↓
Sanitize
    ↓
Rendered HTML
```

---

# 18. PDF Renderer

O PDF Renderer recebe HTML final.

```text
Rendered HTML
      ↓
Headless Browser
      ↓
PDF
```

O renderer deve suportar:

- CSS;
- page breaks;
- header;
- footer;
- imagens;
- tabelas;
- fontes;
- orientação;
- tamanho da página;
- margens.

---

# 19. Mapas

Mapas não devem ser montados diretamente pelo Template.

Deve existir um Block especializado.

Exemplo:

```text
GENERATE_MAP
```

Input:

```text
geometry
layers
zoom
```

Output:

```text
image
```

O Template recebe:

```text
{{map.property}}
```

ou uma referência controlada para a imagem.

---

# 20. Imagens

Imagens devem seguir o mesmo princípio.

```text
GET_IMAGE
      ↓
Image Output
      ↓
Template
      ↓
PDF
```

O Template não deve acessar diretamente URLs privadas.

---

# 21. Database

O REPORTS deve armazenar apenas informações próprias.

Exemplo:

```text
reports
report_versions
flows
flow_versions
templates
template_versions
executions
execution_blocks
block_definitions
```

Dados de domínio devem permanecer nos módulos responsáveis.

---

# 22. Modelo Conceitual

```text
Report
 │
 ├── Version
 │    │
 │    ├── Flow
 │    │
 │    └── Template
 │
 └── Execution
      │
      ├── Context
      │
      ├── Block Executions
      │
      └── Outputs
```

---

# 23. Execution Lifecycle

```text
CREATED
   ↓
VALIDATING
   ↓
RUNNING
   ↓
RENDERING
   ↓
GENERATING_OUTPUT
   ↓
COMPLETED
```

Em caso de erro:

```text
RUNNING
   ↓
FAILED
```

---

# 24. Execution Status

Estados:

```text
CREATED
VALIDATING
RUNNING
RENDERING
GENERATING_OUTPUT
COMPLETED
FAILED
CANCELLED
```

---

# 25. Block Execution

Cada Block executado deve gerar uma informação de execução.

Exemplo:

```json
{
  "blockId": "get-municipality",
  "status": "COMPLETED",
  "startedAt": "...",
  "finishedAt": "...",
  "durationMs": 42,
  "inputs": {
    "latitude": -22.21,
    "longitude": -49.65
  },
  "outputs": {
    "state": "SP",
    "municipality": "Garça",
    "ibgeCode": "3516705"
  }
}
```

Inputs e outputs sensíveis podem ser mascarados conforme política de segurança.

---

# 26. Error Handling

Um erro deve identificar:

```text
Report
Version
Execution
Block
Input
Error
```

Exemplo:

```text
Execution failed

Block:
GET_MUNICIPALITY

Reason:
Coordinates outside supported geometry.
```

---

# 27. Timeouts

Todo Block deve possuir timeout configurável.

Exemplo:

```text
default: 30 seconds
```

Blocks que executam operações pesadas poderão possuir limites específicos.

Uma Execution não deve ficar indefinidamente aguardando um Block.

---

# 28. Resource Limits

O sistema deve possuir limites para evitar abuso.

Exemplos:

```text
maximum blocks per flow
maximum execution time
maximum template size
maximum PDF size
maximum output size
maximum nested execution depth
```

Os valores devem ser configuráveis.

---

# 29. Caching

Caching poderá ser implementado posteriormente.

Exemplo:

```text
GET_MUNICIPALITY
latitude + longitude
        ↓
Cache
```

Porém, cache não deve alterar a semântica da Execution.

A versão do Block e os parâmetros devem fazer parte da chave.

---

# 30. Async Execution

Inicialmente, pequenas execuções podem ser síncronas.

Para execuções pesadas:

```text
API
 ↓
Queue
 ↓
Worker
 ↓
Execution
 ↓
PDF
```

A arquitetura deve permitir essa evolução sem alterar o modelo conceitual.

---

# 31. Worker

Futuramente:

```text
Reports API
     │
     ▼
Queue
     │
     ▼
Reports Worker
     │
     ├── Flow Engine
     ├── Block Engine
     ├── Template Engine
     └── PDF Renderer
```

Isso será importante para:

- PDFs grandes;
- imagens;
- mapas;
- processamento geoespacial;
- AI;
- relatórios em lote.

---

# 32. Permissions

A autorização deve considerar:

```text
Organization
Project
Report
Block
Data Source
```

Um usuário não deve conseguir utilizar um Block ou Data Source ao qual não tenha acesso.

---

# 33. Data Access

Blocks são a principal fronteira de acesso aos dados.

Exemplo:

```text
Template
   X
   │
   │ acesso direto ao banco
   X

Template
   ↓
Context
   ↓
Block
   ↓
Authorized Data Source
```

---

# 34. Reutilização de Flows

Um Flow poderá futuramente ser reutilizável.

Exemplo:

```text
PROPERTY_LOCATION_FLOW
```

Pode ser utilizado por:

```text
Property Report
Environmental Report
Agricultural Report
Inspection Report
```

Isso evita duplicação.

---

# 35. Reutilização de Templates

Templates também devem possuir versionamento independente.

Exemplo:

```text
Corporate Report
├── v1
├── v2
└── v3
```

---

# 36. Reprodutibilidade

Uma Execution deve registrar:

```text
reportVersion
flowVersion
templateVersion
blockVersions
inputs
```

Assim é possível reproduzir uma execução antiga.

---

# 37. Observabilidade

O sistema deve registrar:

- execution ID;
- report ID;
- block ID;
- duração;
- status;
- erros;
- quantidade de Blocks;
- tempo total;
- renderer;
- tamanho do resultado.

---

# 38. Logs

Logs devem utilizar contexto:

```text
reportId
reportVersion
executionId
blockId
```

Exemplo:

```text
[REPORTS]
execution=abc123
block=get-municipality
status=completed
duration=42ms
```

---

# 39. API Future-proof

A API deve separar:

```text
Definition
Execution
Result
```

Não misturar:

```text
POST /reports
```

com execução automática.

Preferir:

```text
POST /reports

POST /reports/:id/execute

GET /executions/:id

GET /executions/:id/result
```

---

# 40. Futuras Extensões

A arquitetura deve permitir:

```text
AI Blocks
SQL Blocks
HTTP Blocks
Python Blocks
Geo Processing Blocks
Chart Blocks
Map Blocks
Scheduled Reports
Report Sharing
Report Distribution
Email
Webhooks
Dashboard Outputs
```

Nenhuma dessas funcionalidades deve ser necessária para o MVP.

---

# 41. Regra arquitetural mais importante

O REPORTS não deve conhecer a implementação interna dos dados.

Exemplo:

```text
GET_MUNICIPALITY
```

deve saber como consultar a informação geográfica.

O Flow Engine apenas sabe:

```text
Input
Output
```

O Template Engine apenas sabe:

```text
Variable
Value
```

O PDF Renderer apenas sabe:

```text
HTML
PDF
```

Essa separação é essencial.

---

# 42. Pipeline definitivo

```text
                    REPORT
                       │
                       ▼
                Report Version
                       │
                       ▼
                     FLOW
                       │
                       ▼
                 Flow Engine
                       │
                       ▼
                Execution Context
                       │
                       ▼
                 Block Registry
                       │
            ┌──────────┼──────────┐
            ▼          ▼          ▼
          Block      Block      Block
            │          │          │
            └──────────┼──────────┘
                       │
                       ▼
                     OUTPUT
                       │
                       ▼
                Template Engine
                       │
                       ▼
                  Rendered HTML
                       │
                       ▼
                 PDF Renderer
                       │
                       ▼
                      PDF
```

---

# 43. Regra de evolução

A implementação deve seguir esta ordem:

```text
1. Domain Model
2. Block Registry
3. Block Execution
4. Execution Context
5. Flow Engine
6. Template Engine
7. HTML Preview
8. PDF Renderer
9. Versioning
10. Async Worker
```

Não implementar o editor visual antes da engine funcionar.

---

# 44. MVP Técnico

O primeiro release deve possuir:

```text
Reports API
Reports UI

Block Registry
Block Executor
Execution Context

Flow Engine

Template Engine

HTML Preview

PDF Renderer

Persistence

Execution Logs
```

Blocks iniciais:

```text
GET_PROPERTY
GET_GEOMETRY
GET_CENTROID
GET_MUNICIPALITY
CALCULATE_AREA
```

---

# 45. Critério de sucesso

A arquitetura estará validada quando for possível executar:

```text
Input:
propertyId = 123

        ↓

GET_PROPERTY

        ↓

GET_GEOMETRY

        ↓

GET_CENTROID

        ↓

GET_MUNICIPALITY

        ↓

CALCULATE_AREA

        ↓

Context

{
    property: {...},
    geometry: {...},
    centroid: {...},
    municipality: {...},
    area: {...}
}

        ↓

HTML Template

        ↓

Rendered HTML

        ↓

PDF
```

sem que o Template precise conhecer banco de dados, GEOLIB ou qualquer implementação interna do InfiniteStack.

---

# 46. Decisão arquitetural final

O REPORTS deve ser construído como uma **engine de composição e apresentação de dados**, e não como um simples gerador de documentos.

A abstração principal é:

```text
Block → Flow → Context → Template → Output
```

O PDF é apenas o primeiro Output.

No futuro, o mesmo Flow poderá produzir:

```text
PDF
HTML
JSON
Dashboard
API Response
Email
```

Essa decisão deve ser preservada durante toda a implementação.
---

# 47. Implementação (fases 0 a 7)

Esta seção descreve o que foi construído em `reports/` e onde a implementação se afasta, de
propósito, do desenho conceitual acima. A ordem das fases está em [ROADMAP.md](ROADMAP.md).

## 47.1 Mapa do código (`com.infinitestack.reports`)

| Pacote | Conteúdo |
|---|---|
| `engine` | `DataType` (tipos do §12, com coerção de valores vindos de formulário/JSON) |
| `engine.block` | `ReportBlock`, `BlockDefinition`, `BlockPort`, `DataRequirement`, `BlockRegistry` (beans Spring) |
| `engine.flow` | `FlowDefinition`/`FlowNode`/`FlowConnection` (JSON gravado), `FlowValidator` (ids, tipos, inputs obrigatórios, ciclos, limite de nós), `FlowExecutor` (ordem topológica, timeout por Block e por execução), `ExecutionContext` |
| `catalog` | Leitura do catálogo (`HostCatalogClient` / `InformationSchemaCatalogClient`), `CatalogService` (cache curto), `BlockAvailabilityService` (AVAILABLE / STALE / MISSING_DATA), `BlockRelease` |
| `template` | `TemplateRenderer` (Mustache estrito via JMustache), `TemplateScope` (variáveis conhecidas antes de rodar), `HtmlSanitizer` (jsoup, allowlist) |
| `output` | `PdfRenderer` + `OpenHtmlToPdfRenderer` |
| `diff` | `LineDiff` (diff de versões) |
| `report`, `execution` | Registros de domínio: `Report`, `ReportVersion`, `InputDefinition`, `PageSettings`, `Execution`, `ExecutionError` |
| `repository` | JDBC puro, SQL qualificado com `apps.`; `SchemaInitializer` cria as tabelas em segundo plano |
| `service`, `controller`, `web` | Regras de uso, API REST e identidade do usuário a partir dos headers `X-IS-*` |

## 47.2 Tabelas (`apps.`)

`reports_report` (soft delete), `reports_report_version` (inputs, flow, template HTML/CSS e página),
`reports_execution` (inputs, contexto, versões dos Blocks, erro, `rerun_of`),
`reports_execution_block` e `reports_block_release`.

## 47.3 API (sob `/api/plugins/reports/api`)

```text
GET  /me                                   usuário dos headers e estado do banco
GET  POST /reports   GET PUT DELETE /reports/{id}
GET  POST /reports/{id}/versions           listar / criar rascunho a partir da última versão
GET  PUT  /reports/{id}/versions/{n}       versão + validação / salvar rascunho
POST /reports/{id}/versions/{n}/publish
GET  /reports/{id}/versions/{n}/variables
GET  /reports/{id}/versions/{a}/diff/{b}
POST /reports/{id}/preview                 roda e renderiza sem gravar
POST /reports/{id}/preview/pdf
POST /reports/{id}/execute                 roda e grava
GET  /reports/{id}/executions
GET  /executions/{id}
GET  /executions/{id}/result?format=html|pdf
POST /executions/{id}/rerun
GET  /blocks   GET /blocks/{id}   POST DELETE /blocks/{id}/release   POST /catalog/refresh
```

## 47.4 Catálogo e liberação de Blocks

- Dentro do IS, o catálogo vem da porta interna `GET /api/extensions/internal/catalog/tables` do back
  (infinitestack-back#1279), autenticada por `X-IS-Plugin-Id` + `X-IS-Plugin-Internal-Token`. Sem
  token (desenvolvimento local), vem do `information_schema` dos schemas bronze/silver/gold.
- Um Block é **utilizável** quando os seus `DataRequirement`s estão AVAILABLE no catálogo **e** um
  ADMIN/SCICROP o liberou. Só é possível liberar um Block AVAILABLE. Usuários comuns só veem Blocks
  utilizáveis.
- Toda execução verifica de novo, antes de rodar, se os Blocks continuam utilizáveis. Se os dados
  sumiram, a execução fica FAILED na etapa VALIDATING, com o motivo.

## 47.5 Onde a implementação difere do desenho

- **Flow e Template ficam dentro da versão do Report** (§21 previa tabelas próprias). Uma linha basta
  para reproduzir uma execução. Flows e Templates reaproveitáveis (§34–35) ficam para depois.
- **Linguagem**: Java/Spring, não as interfaces TypeScript conceituais do §10–13.
- **Template**: Mustache sem lógica, estrito (variável inexistente é erro, valor nulo imprime vazio),
  sem partials. O HTML final sempre passa pelo sanitizador: sem scripts, handlers, iframes, formulários
  ou recursos externos; imagens só como `data:`.
- **PDF**: openhtmltopdf (Java puro) em vez de headless browser (§18). Suporta CSS 2.1 e paged media,
  não flex/grid. Fica atrás de `PdfRenderer` para poder trocar.
- **Resultado não é gravado**: a execução guarda o contexto, e HTML/PDF são renderizados de novo sob
  demanda a partir dele e do template da versão. Por isso `GENERATING_OUTPUT` (§24) ainda não é usado.
- **Preview não é gravado**: roda o mesmo caminho da execução e devolve o HTML.
- **Permissões** (§32): o host não informa organização nem projeto, e o catálogo não tem ACL por
  dataset. Hoje todo usuário vê todos os reports; só o dono ou ADMIN/SCICROP alteram um report; só
  ADMIN/SCICROP liberam Blocks.
- **Reexecução**: só existe a implementação atual de cada Block no app. Uma reexecução avisa quando a
  versão de um Block mudou desde a execução original e informa se os outputs saíram idênticos.
