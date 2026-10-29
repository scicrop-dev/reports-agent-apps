# REPORTS — Integrações: Satellite Hub e scripts Python do Skills Lab

Este documento responde a uma pergunta: o REPORTS consegue usar o Satellite Hub e, se não conseguir,
rodar um script Python do Skills Lab como um Block? Ele diz o que já está feito, o que falta e como
fazer. Parte do código em `reports/` (seção 47 do [ARCHITECTURE.md](ARCHITECTURE.md)) e do que o
back oferece em 2026-10-27.

| Integração | Estado | Onde |
|---|---|---|
| Satellite Hub, **leitura** do que ele já publicou (séries de índices por área, de qualquer coleção: Sentinel-2, Landsat, Sentinel-1) | **feito** | `sathub-index-series` ([BLOCKS.md](BLOCKS.md) §4) e o pronto `sathub-vegetation-monitoring` |
| **Última imagem Sentinel-2** de qualquer área (ex.: um imóvel CAR), na hora, com as fórmulas de índice do Satellite Hub | **feito** (§1.2) | `sathub-latest-image` ([BLOCKS.md](BLOCKS.md) §4) e o pronto `car-latest-satellite-image` |
| Satellite Hub, **pedir processamento** (cadastrar a área e calcular a série) para uma área nova | desenhado (§1.3) | precisa de uma rota no Satellite Hub; a porta plugin-a-plugin do back já existe |
| Script Python do Skills Lab como Block | **feito** (§2) | `skillslab-python` ([BLOCKS.md](BLOCKS.md) §5), sobre a porta de workflows do back (infinitestack-back#1280) |

---

## 1. Satellite Hub

### 1.1 O que já funciona: ler o gold

O Satellite Hub publica três tabelas no gold, e elas bastam para um Block:

| Tabela | Conteúdo |
|---|---|
| `gold.satellite_hub_areas` | áreas cadastradas (fazenda, bloco, talhão), geometria em WGS 84 (4326), `area_ha` |
| `gold.satellite_hub_analytics_indices` | índices, fórmula e `collection`: Sentinel-2 L2A (NDVI, NDRE, GNDVI, NDMI, EVI, SAVI, NDWI, NBR), Landsat 8/9 C2 L2 (`ndvi_landsat`, `ndmi_landsat`) e Sentinel-1 RTC (`rvi_s1`); vazia = Sentinel-2 |
| `gold.satellite_hub_analytics_values` | uma linha por índice × área × imagem: média, mediana, mín., máx., desvio, p10/p25/p75/p90, pixels válidos e % de área válida |

O índice `(index_id, area_id, acquired_at DESC)` torna a série de uma área barata. O
`sathub-index-series` usa isso: acha as áreas que tocam a geometria (índice GiST), lê a série e
combina as áreas por data com média ponderada pelos pixels válidos, mais um gráfico PNG. Tem o
mesmo gate dos outros Blocks: dados no catálogo e liberação por um admin.

**Limite:** só existe série para o que está **cadastrado no Satellite Hub**. Um imóvel CAR sem
talhão cadastrado devolve uma série vazia (o relatório diz isso). No dev, as áreas são de teste e
ficam na França.

### 1.2 O que já funciona: última imagem, direto da fonte

Para "mande um CAR e receba a imagem de satélite mais recente com os índices", não é preciso o
Satellite Hub processar nada. O `sathub-latest-image` vai à **mesma fonte** que ele usa (STAC Earth
Search + COGs Sentinel-2 L2A na AWS), lê só a janela da área por HTTP Range e aplica as fórmulas,
limites e máscara de nuvem de `gold.satellite_hub_analytics_indices`, com a mesma metodologia
(reflectância = DN × 0,0001 sem offset; SCL 4 a 7 = limpo). Devolve PNGs (cor verdadeira e um mapa
por índice) e as estatísticas da imagem, dentro do limite de 120 s da execução.

Do Satellite Hub ele só lê as definições dos índices (é o gate do catálogo), e só as da coleção
`sentinel-2-l2a`: desde que o Satellite Hub passou a ter Landsat e Sentinel-1, um índice de outra
coleção é recusado com mensagem, porque a fórmula dele nomeia bandas de outro sensor. Não grava nada nele e
não gera série: é uma imagem, não um histórico. Exige saída HTTPS do host para
`earth-search.aws.element84.com` e `sentinel-cogs.s3.us-west-2.amazonaws.com`.

### 1.3 O que falta: pedir processamento a partir do REPORTS

Para ter a **série** de um imóvel que não está no Satellite Hub, o REPORTS teria de pedir: "cadastre esta geometria e
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

### 2.1 Como funciona (feito em 2026-10-27)

O desenho anterior pedia uma porta nova no back só para scripts. Não foi preciso: o back ganhou, em
infinitestack-back#1280 (PR #1281, no `dev` em 2026-10-26), a porta interna
`/api/extensions/internal/workflows`, com a qual o backend de um Agent App inicia, acompanha e para
runs de workflows, passando `params` que chegam ao `--context` do primeiro script do run. O Block
`skillslab-python` usa essa porta:

0. `GET …/projects/{projectId}/workflows`: os workflows do projeto liberados para o app. Se o
   `workflow` não está na lista (ou está mas sumiu do projeto, `exists: false`), o Block falha antes
   de iniciar qualquer run, dizendo o arquivo a editar e o que o projeto tem liberado. Um 404 aqui é
   "host sem a porta" (back sem o #1281): em produção o back não manda a mensagem do erro no corpo
   (`server.error.include-message` só no perfil dev), então o 404 de "run não encontrado" e o de "rota
   inexistente" chegam iguais, e só a listagem, que nunca dá 404 quando a rota existe, separa os dois.
   Os 401/403/400 sem mensagem também viram um texto que diz a causa provável. Mesmo cuidado do
   `HostWorkflowsClient` do Satellite Hub.
1. `POST …/projects/{projectId}/workflows/{workflow}/runs` com `{params, onBehalfOf}`: `params` são o
   `params` fixo do nó mais as entradas `text`, `number`, `date`, `geometry`, `data` e `items` ligadas;
   `onBehalfOf` é o usuário que gera o relatório (o back confere se ele pode rodar o workflow).
2. `GET …/runs/{triggerId}` a cada segundo até `done`, no máximo `timeoutSeconds` (5 a 110, padrão
   100). Passou do tempo, ou a execução desistiu do Block: `POST …/runs/{triggerId}/stop`.
3. `GET …/runs/{triggerId}/output` em fatias de 256 KB (só os últimos 8 MB de um log maior).
4. A última linha `IS_REPORTS_RESULT <objeto JSON>` vira a saída `result`; o resto, `log`. Run que
   não termina em `SUCCESS` ou `WARNING` falha o Block com o fim do log.

O script, em qualquer projeto do Skills Lab:

```python
import json
from infinitestack.agent import resolve_agent_context   # ou json.loads do argumento após --context

context = resolve_agent_context()       # {"text": "AC-...", "geometry": {...}, ...} + o input salvo do bloco
result = {"summary": "...", "total": 3}
print("IS_REPORTS_RESULT " + json.dumps(result))
```

O id do run também chega ao script na variável de ambiente `IS_TRIGGER_ID` (back#1276), útil para
o script registrar o próprio run, como o `br-geo-ingest` faz.

**O que a instância precisa** (admin do IS):

- Um workflow no projeto que rode o script (o de Schedules serve; o run é um run comum: Workloads,
  `lsruns`, fila do `proclimits`).
- A liberação desse workflow para o REPORTS em `/opt/infinitestack/etc/reports-workflows.json`
  (`AppWorkflowAccess` do back), no formato `{"workflows":[{"projectId":"...","workflow":"..."}]}`.
  Sem o arquivo, nada roda: o Block diz que o workflow não está liberado.
- O back com o #1281 (hoje só no `dev`); sem ele o Block diz que o back precisa ser atualizado.
- No REPORTS, liberar o Block `skillslab-python` uma vez na tela de Blocks (ele não tem
  `DataRequirement`, então está sempre disponível para liberar).

**Limites e diferenças em relação ao desenho anterior:**

- Síncrono: o relatório espera o script, que precisa terminar dentro dos 120 s da execução. Script
  longo segue a alternativa do §2.3.
- `--context` de até 64 KB (limite do back): passe uma chave (código do imóvel, id de área), não os dados.
- Um Block genérico, não um Block por script: as entradas e saídas são livres (`result` é um objeto) e
  não há manifesto com contrato por script. O template lê `{{nó.result.campo}}`.
- **Sem fixar o código na liberação:** a porta não informa o sha256 do script, então liberar o Block
  não congela a versão do script. O controle fica na liberação do workflow no arquivo da instância.
  Fixar por sha256 continua pendente e precisa de um campo novo na resposta do run, no back.
- Resultado pelo stdout (linha marcada), porque a porta só devolve o log. `json.dumps` padrão (só ASCII)
  e em uma linha.

### 2.3 Alternativa para scripts longos

Para um cálculo Python que não precisa rodar na hora do relatório: o script roda **agendado** no
Skills Lab e grava o resultado numa tabela gold (`register_gold_table` da python-lib). Um Block Java
lê essa tabela com o gate de catálogo de sempre. É o mesmo padrão do BR Geo e do Satellite Hub
(ingestão em Python, leitura nos Blocks) e não exige nada novo do back.

## 3. is-geo-sam (candidatos a talhão com SAM)

### 3.1 O que é (feito em 2026-10-28)

O is-geo-sam (`scripts/is-geo-sam` no workspace, repo `scicrop-dev/is-geo-sam`) é um pacote Python
do Skills Lab que, a partir do JSON de workflow com o bloco `sathub-latest-image`, propõe candidatos
a talhão com o SAM 2.1 em CPU e grava cena, run e candidatos em `gold.is_geo_sam_*`, todos
`PENDING_REVIEW`. O JSON que ele lê é o mesmo formato das execuções do REPORTS (`block_id` +
`outputs`), então o Block `isgeosam-field-candidates` monta esse JSON a partir das saídas de
`sathub-latest-image` e da área. Ele junta os dois padrões deste documento: pede o run pela porta de
workflows (§2.1) e lê o resultado do gold (§2.3).

**Por que não síncrono como o `skillslab-python`:** o SAM leva cerca de 1 min por bloco de 1024 px,
mais carregar o modelo e a fila; com os ~20 s da imagem, não cabe com folga nos 120 s da execução.
Então o Block espera até `waitSeconds` (padrão 60) e, se não acabou, deixa o run seguir e responde
`PROCESSING`. A próxima execução do relatório acha o run concluído no gold e mostra na hora, porque a
cena tem identidade fixa: o JSON é determinístico e o script identifica a cena por
sha256(nome do arquivo + conteúdo), que o Block calcula igual (`SceneRequest.sceneSha256`, testado
contra um vetor gerado pelo `scene._sha256` do script).

**Por que o JSON vai por arquivo:** o `trueColor` passa de 250 KB e a porta aceita só 64 KB de
`params`. O Block grava `reports-isgeosam-<sha>.json` em `reports.isgeosam.staging-dir` (padrão
`/opt/infinitestack/var/lake/staging`, o `ISGEOSAM_STAGING_DIR` do script), como `.part` + rename, e
passa só o nome: `params = {"file": "<nome>"}`. O script apaga o arquivo quando termina. É o primeiro
Block que **escreve** no disco do host; o resto do app só lê.

### 3.2 O que a instância precisa (admin do IS)

1. O is-geo-sam instalado num projeto do Skills Lab (README do pacote: zip, dependências pelo botão
   verde do `requirements.txt`), com `ISGEOSAM_APP_ID=reports` no `.env`, para as tabelas entrarem no
   catálogo na fonte do REPORTS.
2. Um workflow nesse projeto com um único bloco `Script` (`is-geo-sam/main.py`), por padrão de id
   `is-geo-sam`.
3. A liberação desse workflow para o REPORTS em `/opt/infinitestack/etc/reports-workflows.json`
   (como no §2.1).
4. No REPORTS: `REPORTS_ISGEOSAM_PROJECT_ID=<projectId>` (ou o input `projectId` do nó) e, se
   diferente do padrão, `REPORTS_ISGEOSAM_WORKFLOW` e `REPORTS_ISGEOSAM_STAGING_DIR`. O processo do app
   precisa poder escrever no staging do lake.
5. Rodar o script uma vez (`main.py --schema` basta) para criar e registrar as tabelas; só então o
   gate de catálogo deixa liberar o Block na tela de Blocks.

### 3.3 Limites

- Uma segmentação por vez na instância (lock do script): pedidos de imagens diferentes fazem fila, e
  cada um conta no `waitSeconds` de quem pediu.
- A primeira execução para uma imagem nova normalmente sai `PROCESSING`.
- O Block procura o run pela cena, não pelos parâmetros de segmentação (`params_hash`): se o admin
  mudar modelo ou parâmetros no `.env`, o relatório continua mostrando o run antigo da mesma imagem
  até uma imagem nova aparecer.
- Um `RUNNING` ou um arquivo no staging com mais de 30 min é dado como morto (backend reiniciado) e o
  Block pede de novo.

## 4. Dados GIS do Data Library (área do relatório)

### 4.1 Como funciona (feito em 2026-10-29)

A aba GIS do Data Library (infinitestack-back#1248) grava cada shapefile, GeoJSON ou desenho em
`bronze.is_geo_ref` (um conjunto: `geo_id`, `geo_name`, `geo_attributes` com categoria, descrição,
contagem e área) e `bronze.is_geo_ref_data` (as feições: `geo_data` em EPSG:4326, qualquer tipo de
geometria, `geo_data_attributes`). O catálogo as mostra na source `gis:infinitestack`.

O REPORTS lê essas tabelas pelo pool somente leitura dos Blocks (`blocks.gis.GisAreas`):

- `GET {base}/api/gis/datasets` e `GET {base}/api/gis/datasets/{id}/features` alimentam os inputs com
  `picker` `gis-dataset` / `gis-feature` do formulário de execução;
- o Block `report-area` (1.1.0) recebe `gisDataset` (+ `gisFeature`) e usa os polígonos do conjunto,
  ou da feição, como a área do relatório (BLOCKS.md, `report-area`).

### 4.2 Limites

- Não há ACL por conjunto: quem roda relatórios vê todos os conjuntos GIS da instância, como no
  Data Library.
- Pontos e linhas não viram área; um conjunto com mais de 5.000 polígonos exige escolher a feição.
- Sem `bronze.is_geo_ref*` (ninguém usou a aba GIS), a lista vem vazia e o `report-area` com
  `gisDataset` falha dizendo isso. O `report-area` não declara `DataRequirement` dessas tabelas, para
  continuar liberado em instâncias sem GIS.
