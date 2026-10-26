# REPORTS — Integrações: Satellite Hub e scripts Python do Skills Lab

Este documento responde a uma pergunta: o REPORTS consegue usar o Satellite Hub e, se não conseguir,
rodar um script Python do Skills Lab como um Block? Ele diz o que já está feito, o que falta e como
fazer. Parte do código em `reports/` (seção 47 do [ARCHITECTURE.md](ARCHITECTURE.md)) e do que o
back oferece em 2026-10-26.

| Integração | Estado | Onde |
|---|---|---|
| Satellite Hub, **leitura** do que ele já publicou (séries de índices por área) | **feito** | `sathub-index-series` ([BLOCKS.md](BLOCKS.md) §4) e o pronto `sathub-vegetation-monitoring` |
| Satellite Hub, **pedir processamento** para uma área nova (ex.: um imóvel CAR que não está cadastrado nele) | desenhado (§1.2) | precisa de uma rota no Satellite Hub; a porta plugin-a-plugin do back já existe |
| Script Python do Skills Lab como Block | desenhado (§2) | precisa de uma porta interna nova no back |

---

## 1. Satellite Hub

### 1.1 O que já funciona: ler o gold

O Satellite Hub publica três tabelas no gold, e elas bastam para um Block:

| Tabela | Conteúdo |
|---|---|
| `gold.satellite_hub_areas` | áreas cadastradas (fazenda, bloco, talhão), geometria em WGS 84 (4326), `area_ha` |
| `gold.satellite_hub_analytics_indices` | índices (NDVI, NDRE, GNDVI, NDMI, EVI, SAVI, NDWI, NBR), fórmula e coleção (Sentinel-2 L2A) |
| `gold.satellite_hub_analytics_values` | uma linha por índice × área × imagem: média, mediana, mín., máx., desvio, p10/p25/p75/p90, pixels válidos e % de área válida |

O índice `(index_id, area_id, acquired_at DESC)` torna a série de uma área barata. O
`sathub-index-series` usa isso: acha as áreas que tocam a geometria (índice GiST), lê a série e
combina as áreas por data com média ponderada pelos pixels válidos, mais um gráfico PNG. Tem o
mesmo gate dos outros Blocks: dados no catálogo e liberação por um admin.

**Limite:** só existe série para o que está **cadastrado no Satellite Hub**. Um imóvel CAR sem
talhão cadastrado devolve uma série vazia (o relatório diz isso). No dev, as áreas são de teste e
ficam na França.

### 1.2 O que falta: pedir processamento a partir do REPORTS

Para um imóvel que não está no Satellite Hub, o REPORTS teria de pedir: "cadastre esta geometria e
calcule o NDVI dos últimos 12 meses". O caminho:

1. **Porta plugin-a-plugin do back (já existe).** `PluginInternalCallController` expõe
   `/api/extensions/internal/plugins/{targetPluginId}/**`, autenticado pelo `X-IS-Plugin-Id` e o
   `X-IS-Plugin-Internal-Token` que o REPORTS já usa para o catálogo. Vem ligada por padrão
   (`extensions.internal.plugins.call.enabled=true`) e, com `allowed-targets` vazio, aceita qualquer
   destino. Numa instância que restrinja os destinos, é preciso incluir `satellite-hub` ali.
2. **Rota no Satellite Hub (falta).** Algo como `POST /api/analytics/requests` com
   `{geometry, indices, from, to, label}`, que devolve `{requestId, areaId}`, e
   `GET /api/analytics/requests/{id}` para o andamento. A tabela `apps.satellite_hub_analytics_requests`
   sugere que o app já tem esse conceito por dentro; falta expor e documentar o contrato.
3. **O processamento é assíncrono e longo** (baixar cenas, calcular índices): minutos a horas. Um
   Block roda no máximo 120 s (limite da execução). Então não dá para "pedir e esperar" dentro de um
   relatório. O desenho que cabe:
   - um Block `sathub-request-area` que **só registra o pedido** e devolve `requestId`/`areaId` e o
     estado (`queued`, `running`, `done`);
   - o relatório mostra "processamento solicitado em ...; rode de novo mais tarde" enquanto não
     termina, e o `sathub-index-series` (com o `areaId` devolvido) preenche a série quando houver;
   - para não repetir pedidos, o Satellite Hub deduplica por hash da geometria
     (`satellite_hub_areas.hash` já existe).
4. **Cuidado com custo:** cada pedido baixa imagens. A liberação do Block pelo admin é o controle,
   e vale um limite por usuário/dia na rota do Satellite Hub.

**PRs:** satellite-hub (rota + contrato no ARCHITECTURE.md dele) e reports (Block novo + relatório).
O back não muda; o infra só muda se a instância restringir `allowed-targets`.

---

## 2. Script Python do Skills Lab como Block

### 2.1 Por que ainda não dá

- O Skills Lab roda scripts pelo `PythonManager` do back, no venv do projeto
  (`HOW_SKILLS_LAB_WORKS.md` no infra). As rotas REST (`POST /api/projects/{projectId}/scripts/execute/{scriptId}`)
  exigem **sessão de usuário** (JWT + papel), e o REPORTS não tem token de usuário: ele só recebe os
  headers `X-IS-*` do proxy.
- O back não tem uma porta interna para um plugin **rodar** um script. As portas internas de hoje
  são catálogo, LLM, WhatsApp e chamada plugin-a-plugin.
- O run do Skills Lab responde em até 8 s e depois vira assíncrono. Um Block precisa da saída na
  mesma chamada, com contrato de entradas e saídas.

### 2.2 Desenho

**Contrato do script.** Um script vira Block com um manifesto ao lado, `reports-block.json`:

```json
{
  "id": "py-ndvi-anomaly",
  "name": "NDVI anomaly",
  "version": "1.0.0",
  "script": "ndvi_anomaly/main.py",
  "inputs":  [ { "name": "series", "type": "array", "required": true, "description": "..." } ],
  "outputs": [ { "name": "anomalies", "type": "array", "required": true, "description": "..." } ],
  "dataRequirements": [ { "schema": "gold", "table": "satellite_hub_analytics_values", "columns": ["mean"] } ],
  "timeoutSeconds": 60,
  "docs": { "category": "Python (Skills Lab)", "summary": "...", "howItWorks": ["..."], "limitations": ["..."] }
}
```

O script lê as entradas e escreve as saídas por um helper da `infinitestack-python-lib`:

```python
from infinitestack.reports import block

inputs = block.inputs()                 # JSON de IS_BLOCK_INPUTS_FILE
anomalies = detect(inputs["series"])
block.outputs({"anomalies": anomalies}) # escreve em IS_BLOCK_OUTPUTS_FILE
```

O stdout fica só para log, para que um `print` perdido não quebre o contrato.

**Back: porta interna nova** (`PluginInternalScriptsController`):

| Rota | O que faz |
|---|---|
| `GET /api/extensions/internal/scripts/blocks` | lista os `reports-block.json` dos projetos permitidos, com o sha256 do script |
| `POST /api/extensions/internal/scripts/run` | `{projectId, manifestPath, inputs, timeoutSeconds}`: grava as entradas em arquivo temporário, roda o script pelo `PythonManager` como run rastreado (`workflowType: reports`, aparece em Workloads), espera até o timeout, lê o arquivo de saída e devolve `{exitCode, outputs, logTail, triggerId, scriptSha256}` |

Autenticada pelo mesmo par `X-IS-Plugin-Id` + `X-IS-Plugin-Internal-Token`, desligada por padrão
(`extensions.internal.scripts.enabled`) e com **allow-list** de projetos/pastas
(`extensions.internal.scripts.allowed`). O run passa pelo vigia de recursos e pelos limites de
processo (`proclimits`) que já existem.

**REPORTS: Blocks dinâmicos.**

- Hoje o `BlockRegistry` só conhece beans Spring. Entraria uma interface `ReportBlockProvider`, com um
  provider que lê `GET .../scripts/blocks` (com cache, como o catálogo) e cria um `PythonScriptBlock`
  por manifesto. O `PythonScriptBlock` chama `POST .../scripts/run` e confere as saídas contra o
  contrato (o `FlowExecutor` já faz isso para qualquer Block).
- **Liberação fixa o código:** ao liberar um `py-*`, o admin libera aquele sha256. Se o script mudar,
  o Block volta para "alterado, libere de novo" e não roda até lá, porque o script pode fazer
  qualquer coisa no venv do projeto (inclusive escrever no banco pela lib). É a diferença em relação
  aos Blocks Java, revisados em PR.
- A execução guarda `blockVersion` = versão do manifesto + sha256, o que mantém a reexecução honesta.
- Na paleta do editor, o grupo "Python (Skills Lab)" vem do `docs.category` do manifesto.

**Limites:**

- Síncrono, até o timeout do Block (no máximo os 120 s da execução). Script longo (treino, download
  grande) não é Block: ele roda agendado no Skills Lab e grava uma tabela gold, e um Block Java
  normal lê essa tabela.
- Saídas pequenas (JSON de até alguns MB); imagens como `data:` URI, como o mapa.

**PRs, nesta ordem:** back (porta + allow-list + listagem de manifestos + testes), infra (flags na
instância, doc em `HOW_SKILLS_LAB_WORKS.md`), python-lib (`infinitestack.reports.block`), reports
(`ReportBlockProvider`, `PythonScriptBlock`, liberação por sha256, docs). Estimativa: uma semana, a
maior parte no back e na segurança da liberação.

### 2.3 Alternativa que já funciona hoje

Para um cálculo Python que não precisa rodar na hora do relatório: o script roda **agendado** no
Skills Lab e grava o resultado numa tabela gold (`register_gold_table` da python-lib). Um Block Java
lê essa tabela com o gate de catálogo de sempre. É o mesmo padrão do BR Geo e do Satellite Hub
(ingestão em Python, leitura nos Blocks) e não exige nada novo do back.
