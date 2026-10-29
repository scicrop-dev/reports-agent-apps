# REPORTS — Relatórios prontos

Relatórios que vêm com o app, prontos para usar. O usuário escolhe um, dá um nome (opcional) e
recebe **a sua cópia**: um report comum, com a versão 1 em rascunho, que ele roda, muda e publica
como qualquer outro. Quem prefere montar do zero continua podendo ("New report").

O código está em `reports/src/main/java/com/infinitestack/reports/readymade` e os relatórios em
`reports/src/main/resources/ready-made/`. A API e o mapa do código estão na seção 47 do
[ARCHITECTURE.md](ARCHITECTURE.md); os Blocks que eles usam, em [BLOCKS.md](BLOCKS.md).

## 1. Conceito

- Um relatório pronto é **conteúdo**, não código: o mesmo que uma versão de report guarda (inputs,
  Flow, template HTML/CSS e página), mais id, versão e metadados de catálogo. Não há engine própria.
- Ele **depende de Blocks**. A tela mostra, para cada Block do Flow, se ele está utilizável para
  quem está olhando (dados no catálogo **e** liberado por um admin, a mesma regra de
  `BlockService.problemsFor`). Se faltar algum, o usuário ainda pode criar a cópia, mas ela só roda
  depois que um admin liberar os Blocks. A validação da versão mostra o que falta.
- Criar a partir de um pronto (`POST /ready-made/{id}/instantiate`) grava um `reports_report` do
  usuário, com `ready_made_id` e `ready_made_version` (a origem), e a versão 1 em `DRAFT` com o
  conteúdo do pronto. Daí em diante não há vínculo: é o report do usuário.

## 2. Formato do arquivo

Um arquivo `ready-made/<id>.json` por relatório. O nome do arquivo tem de ser o id.

```json
{
  "id": "car-socioenvironmental",
  "version": "1.0.0",
  "name": "Laudo socioambiental do imóvel CAR",
  "description": "O que ele mostra, em uma ou duas frases.",
  "category": "Socioambiental",
  "inputs": [
    { "name": "codImovel", "type": "string", "required": true, "label": "Código do imóvel no CAR" }
  ],
  "flow": {
    "nodes": [ { "id": "property", "block": "car-property" } ],
    "connections": [ { "from": "inputs.codImovel", "to": "property.codImovel" } ]
  },
  "templateHtmlFile": "car-socioenvironmental.html",
  "templateCssFile": "car-socioenvironmental.css",
  "page": { "size": "A4", "orientation": "portrait", "marginMm": 18 }
}
```

| Campo | Regra |
|---|---|
| `id` | kebab-case, único, igual ao nome do arquivo. Prefixo pela fonte principal (`car-`, `brgeo-`) ou pelo que o relatório recebe (`area-` para CAR ou polígono, `municipality-`). Um id não muda depois de publicado: as cópias guardam a origem por ele. |
| `version` | semver `x.y.z` (ver §4). |
| `name`, `description`, `category` | Texto do catálogo, no idioma do relatório (o laudo é em português). `name` vira o nome padrão da cópia (até 200 caracteres). |
| `inputs`, `flow`, `page` | Mesmo JSON que a API de versões usa (`InputDefinition`, `FlowDefinition`, `PageSettings`). Os ids dos nós são os namespaces do template. Um nó com `"optional": true` é pulado quando lhe falta um input obrigatório e não derruba o relatório se falhar (ARCHITECTURE §47.5.1). |
| `templateHtml` / `templateHtmlFile` | O HTML inline **ou** o nome de um arquivo irmão (`<id>.html`). Um ou outro. |
| `templateCss` / `templateCssFile` | Idem para o CSS (`<id>.css`). |

O `ReadyMadeCatalog` lê tudo no boot e **para o boot** se um arquivo estiver malformado (campo
desconhecido, id fora do padrão, versão que não é semver, Flow sem nós, arquivo irmão inexistente)
ou se dois arquivos tiverem o mesmo id. Ele não exige que os Blocks existam: Blocks entram e saem
com o catálogo e as liberações, e a tela avisa.

## 3. Como adicionar um relatório pronto

1. Confira que os Blocks que ele usa existem (ou estão contratados) em [BLOCKS.md](BLOCKS.md).
2. Crie `ready-made/<id>.json` e, se o template for grande, `<id>.html` e `<id>.css` ao lado.
3. Escreva o template para o PDF: **CSS 2.1 + paged media** (openhtmltopdf; sem flex/grid), header
   com `position: running(header)` e `@page { @top-left { content: element(header) } }`, rodapé com
   `counter(page)`/`counter(pages)`, `page-break-inside: avoid` em tabelas e blocos. Sem scripts;
   imagens só como `data:` (o sanitizador tira o resto).
4. Lembre do Mustache estrito: toda variável usada tem de existir no escopo; dentro de
   `{{#lista}}...{{/lista}}` os nomes são relativos ao item. Para a tabela aparecer só quando a
   lista tem itens, abra-a em `{{#-first}}` e feche-a em `{{#-last}}` dentro da seção, e use
   `{{^lista}}` para a mensagem de lista vazia (o laudo faz assim). **O número 0 não é falso** no
   JMustache: `{{#x.count}}` com 0 mostra a seção. Teste listas ou booleanos (`hasActive`,
   `monitored`). Inputs opcionais em branco chegam como nulo: teste-os com `{{#inputs.x}}`.
5. Rode `mvn -q test`. O `ReadyMadeReportsValidityTest` pega **todos** os prontos e verifica Flow,
   template e página contra os Blocks, executa o Flow com saídas de exemplo e renderiza HTML e PDF em
   `reports/target/ready-made/` (abra o PDF e olhe). Se o pronto usa Blocks que ainda não têm
   duplos de teste, acrescente-os (hoje: `support/CarBlockContractStubs` e `support/GeoBlockContractStubs`,
   espelhos do BLOCKS.md) e ponha o Block real em `ReadyMadeAgainstRealBlocksTest.realBlocks()`: o teste
   exige portas idênticas e um dublê para cada Block da lista.
6. Atualize o catálogo da §5 abaixo.

## 4. Versionamento

- **Pronto**: muda o conteúdo → nova `version`. Correção de texto/estilo = patch; seção nova ou
  Block novo no Flow = minor; input novo obrigatório ou mudança que quebra cópias antigas = major.
- **Cópia do usuário**: é um report comum, com as suas próprias versões (rascunho → publicada). A
  versão 1 nasce do pronto na versão registrada em `ready_made_version`. Mudar o pronto depois
  **não** mexe nas cópias que já existem, e as execuções antigas seguem reproduzíveis.

**Atualização de cópias (ideia, ainda não implementada).** Como cada report sabe de que pronto e
versão veio, o app pode, no futuro, avisar "há uma versão nova deste relatório pronto" e oferecer
**criar um novo rascunho** da cópia com o conteúdo novo. Nada seria sobrescrito: o rascunho passa
pelo diff de versões que já existe (Fase 7) e o usuário decide se publica. Se o usuário tiver
alterado a cópia, o diff mostra o que ele perderia; um merge automático fica fora de escopo.

## 5. Catálogo

| # | Id | Nome | Entrada | Blocks | Estado |
|---|---|---|---|---|---|
| 1 | `car-socioenvironmental` | Laudo socioambiental do imóvel CAR | `codImovel` | os 6 do CAR, `car-data-reference` e `car-quarantine` (opcionais) | **1.1.0**. Só CAR: roda numa instância sem o BR Geo |
| 2 | `car-socioenvironmental-complete` | Laudo socioambiental completo (imóvel CAR ou polígono) | CAR, polígono **ou** GIS, `crop?` | `report-area`, os 5 do CAR, `car-data-reference`, `car-quarantine` (opcionais), `car-in-area` (opcional), 6 do BR Geo, `brgeo-soil-units`, `brgeo-census-evidence`, `brgeo-zarc-risk`, `brweather-rainfall`, `brweather-daily-weather`, `sathub-latest-image` | **2.1.0** |
| 3 | `brgeo-area-diagnosis` | Diagnóstico socioambiental de uma área | polígono | `brgeo-municipalities`, `brgeo-biomes`, `brgeo-embargoes`, `brgeo-deter-alerts`, `brgeo-prodes`, `brgeo-soil-units`, `brgeo-census-evidence` | **1.1.0**. Só BR Geo |
| 4 | `sathub-vegetation-monitoring` | Monitoramento de vegetação de um talhão | `areaId` do Satellite Hub, `index?`, `days?` | `sathub-index-series`, `sathub-index-anomaly` (opcional) | **1.1.0**. Sentinel-2, Landsat 8/9 ou Sentinel-1, conforme o índice |
| 5 | `car-latest-satellite-image` | Imagem de satélite mais recente (imóvel CAR ou polígono) | CAR, polígono **ou** GIS | `report-area`, `car-property` (opcional), `sathub-latest-image` | **2.0.0**. Lê Sentinel-2 na hora (saída HTTPS para a AWS) |
| 6 | `area-climate` | Clima da área | CAR, polígono **ou** GIS, `from?`, `to?`, `days?` | `report-area`, `car-property`, `brgeo-municipalities`, `brweather-rainfall`, `brweather-daily-weather`, `brweather-temperature-anomaly`, `brweather-water-balance` | **1.1.0** |
| 7 | `area-planting-window` | Janela de plantio | CAR, polígono **ou** GIS, `crop`, `season?` | `report-area`, `car-property`, `brgeo-municipalities`, `brgeo-zarc-risk`, `brgeo-zarc-cultivars`, `brgeo-soil-units`, `brgeo-soil-profiles`, `brweather-rainfall`, `brweather-daily-weather`, `brweather-water-balance`, `brgeo-crop-progress`, `brgeo-crop-production`, `brgeo-crop-production-history` | **1.1.0** |
| 8 | `area-crop-monitoring` | Acompanhamento de safra | CAR, polígono **ou** GIS, `from` (plantio), `to?`, `crop?`, `index?` | `report-area`, `car-property`, `brgeo-municipalities`, `sathub-index-series`, `sathub-index-anomaly`, `sathub-index-compare`, `brweather-rainfall`, `brweather-daily-weather`, `brweather-water-balance`, `brgeo-crop-progress`, `sathub-latest-image` | **1.1.0** |
| 9 | `area-climate-event` | Laudo de evento climático | CAR, polígono **ou** GIS, `from`, `to`, `crop?`, `index?` | `report-area`, `car-property`, `brgeo-municipalities`, `brweather-rainfall`, `brweather-daily-weather`, `brweather-temperature-anomaly`, `sathub-index-series`, `sathub-index-anomaly`, `brgeo-zarc-risk`, `sathub-latest-image` | **1.1.0** |
| 10 | `area-rural-credit-dossier` | Dossiê para crédito rural | CAR, polígono **ou** GIS, `crop`, `season?` | `report-area`, os 5 do CAR, `car-data-reference`, `car-quarantine`, `car-in-area` (opcionais), 5 do BR Geo, `brgeo-soil-units`, `brgeo-census-evidence`, `brgeo-zarc-risk`, `brgeo-crop-production`, `brgeo-crop-progress`, `brweather-rainfall`, `sathub-latest-image` | **1.1.0** |
| 11 | `municipality-agri-bulletin` | Boletim agrícola do município | `municipalityIbge`, `crop?`, `season?` | `brgeo-crop-production`, `brgeo-crop-progress`, `brgeo-zarc-risk`, `brgeo-crop-production-history`, `brgeo-zarc-cultivars` | **1.1.0** |
| 12 | `area-field-candidates` | Candidatos a talhão por segmentação de imagem (imóvel CAR ou polígono) | CAR, polígono **ou** GIS | `report-area`, `car-property` (opcional), `sathub-latest-image`, `isgeosam-field-candidates` (opcional) | **1.0.0**. Lê Sentinel-2 na hora e roda o is-geo-sam no Skills Lab |
| 13 | `area-soil-aptitude` | Solo e aptidão agrícola | CAR, polígono **ou** GIS, `crop`, `season?` | `report-area`, `car-property` (opcional), `brgeo-municipalities`, `brgeo-soil-units`, `brgeo-soil-profiles`, `brgeo-zarc-risk`, `brgeo-zarc-cultivars`, `brweather-water-balance`, `brgeo-crop-production-history` | **1.0.0** |
| 14 | `area-car-properties` | Imóveis do CAR na área | polígono, GIS **ou** CAR, `limit?` | `report-area`, `car-in-area`, `brgeo-municipalities`, `brgeo-biomes`, `brgeo-embargoes`, `car-data-reference` (opcional) | **1.0.0** |

Os prontos 2 a 14 usam o CSS comum `report-base.css` (o 1 mantém o seu). Um pronto só roda quando
**todos** os seus Blocks estão utilizáveis, inclusive os opcionais. Por isso os prontos só do CAR (1)
e só do BR Geo (3) continuam existindo: rodam onde só há uma das fontes, e as adições da 1.1.0 de cada
um ficaram dentro da sua fonte (o 1 ganhou só Blocks do CAR; o 3, só do BR Geo).

**CAR, polígono ou dado GIS.** Os prontos 2, 5 a 10 e 12 a 14 têm os inputs opcionais `codImovel`, `area`
(geometry), `areaName`, `gisDataset` (`"picker": "gis-dataset"`) e `gisFeature` (`"picker": "gis-feature"`);
quem roda preenche o código, o polígono **ou** escolhe um dado GIS do Data Library (e, se quiser, uma
feição dele). O template diz de onde veio a área com `{{#area.isGis}}`. O Block `report-area` resolve a área, os
Blocks do CAR são nós opcionais ligados a `area.codImovel` (pulados para polígono) e o template mostra
as seções do CAR em `{{#area.isCar}}`. Para polígono ou GIS, os prontos 2 e 10 mostram no lugar
os imóveis do CAR que cortam a área (`car-in-area`, que roda também para CAR mas só aparece fora dele).
Município e UF, para ZARC, cultivares, CONAB e PAM, são os do município com a maior parte da área
(`municipalities.mainIbgeCode`/`mainUf`).

**Seções que dependem de rede ou de dado novo** (imagem, chuva, temperatura, balanço hídrico, série e
quedas do índice, ZARC, cultivares, CONAB, PAM, solo, Censo, data e quarentena do SICAR) são nós
opcionais: se a fonte falhar, o relatório sai com o aviso no lugar e a falha fica nos blocos
da execução. A imagem é sempre o último nó, para não gastar o tempo da execução antes dos outros.

**Padrões de template usados nas seções novas (1.1.0):**

- Nó opcional que falhou tem todas as saídas nulas. Onde "zero" e "não consultado" precisam de
  textos diferentes, o template testa um número (`{{#quarantine.count}}`, `{{#inArea.count}}`): o 0
  é verdadeiro no JMustache e o nulo é falso.
- `sathub-index-anomaly.latestStatus` é texto; a situação da última imagem sai de
  `{{#drops.points}}{{#-last}}{{#isDrop}}...` (o último ponto da série).
- Rótulos que os Blocks devolvem em inglês (restrições de solo, motivos da quarentena) não vão para o
  laudo: as restrições saem dos booleanos `soil.flags.*` com texto em português, e os motivos da
  quarentena saem pelo código (`duplicado`, `geometria_invalida`, `orfao`) com a legenda na nota.
- Censo Agro 2017: toda seção ou linha de síntese leva o aviso "presença histórica, não prova".

### 5.1 Laudo socioambiental do imóvel CAR (`car-socioenvironmental`, 1.1.0)

Entrada: `codImovel` (código do imóvel no SICAR). Seções:

0. Cabeçalho: título, código, emissão (`report.generatedAt`), execução (`report.executionId`; numa
   prévia aparece "Prévia, não registrada"), alertas de imóvel removido do SICAR (`property.removed`),
   de área declarada x calculada acima de 5% (`property.areaMismatch`) e de pendências na carga.
1. Identificação do imóvel (`car-property`).
2. Localização: mapa (`car-property-map`, imagem `data:` com legenda), município/UF, ponto de
   referência (lat/lon), área calculada × declarada com a diferença em % (`car-property-geometry`).
3. Situação do cadastro: situação, condição, datas e histórico de versões com área calculada e
   variação frente à versão anterior (`car-history` 1.1.0); data de referência da base SICAR por
   camada (`car-data-reference`, opcional); pendências na carga (`car-quarantine`, opcional, só
   quando `hasItems`).
4. Uso e cobertura declarados: destaques de Reserva Legal, vegetação nativa e APP (% e ha), aviso
   quando não há Reserva Legal declarada, tabela por camada com temas e a hidrografia marcada
   "(calculada)" (`car-environmental-layers` 1.1.0, `summary.hydrographyCalculated`).
5. Sobreposições com outros imóveis CAR (`car-overlaps`), ou "nenhuma sobreposição".
6. Fontes e limitações.

As seções do BR Geo ficaram num pronto à parte (§5.2), para este continuar rodando onde só há o CAR.

### 5.2 Laudo socioambiental completo (`car-socioenvironmental-complete`, 2.1.0)

Entradas: `codImovel`, `area` (+ `areaName`) **ou** `gisDataset` (+ `gisFeature`), e `crop` opcional. Seções:

0. **Síntese**: área analisada, situação no CAR, Reserva Legal, sobreposições, alertas do cadastro
   (removido, divergência de área, pendências) e data da base SICAR (só CAR) ou os imóveis do CAR na
   área (polígono/GIS), bioma, embargos ativos, DETER, PRODES, solo e restrições, Censo Agro 2017, NDVI
   da imagem mais recente, chuva dos últimos 12 meses e, com `crop`, se a cultura é zoneada no ZARC.
   "Área não monitorada" aparece em cinza, nunca como "sem alerta".
1. Área analisada (dados do CAR ou do polígono, área, ponto, municípios).
2. Localização e bioma: mapa do CAR (`car-property-map`), biomas, caixa envolvente, municípios.
3. Cadastro Ambiental Rural: situação, histórico com variação de área, data de referência da base
   SICAR, pendências na carga, uso e cobertura declarados (hidrografia calculada), sobreposições. Para
   polígono ou GIS, a tabela "Imóveis do CAR na área" com a cobertura (`car-in-area`).
4. a 6. Embargos do IBAMA, DETER, PRODES.
7. Solo da área (`brgeo-soil-units`: unidades, ordem principal, restrições).
8. Evidências do Censo Agropecuário 2017 (`brgeo-census-evidence`), com o aviso "presença histórica,
   não prova".
9. Imagem Sentinel-2 mais recente em cor verdadeira, com NDVI e NDMI.
10. e 11. Chuva (CHIRPS) e temperatura (NASA POWER) dos últimos 365 dias.
12. Perfil agrícola do município (PAM).
13. ZARC da cultura informada (ou como pedi-lo).
14. Fontes e limitações.

A 2.0.0 trocou o input `codImovel` obrigatório pelos três opcionais: cópias da 1.0.0 continuam como
estavam.

### 5.3 Diagnóstico socioambiental de uma área (`brgeo-area-diagnosis`, 1.1.0)

Entradas: `area` (geometry, GeoJSON em SIRGAS 2000) e `areaName` (opcional). Mesmas seções do BR Geo
do laudo completo, para qualquer polígono (um talhão, um imóvel fora do CAR, uma área de compra),
mais solo com restrições e evidências do Censo Agro 2017 (1.1.0). Só usa Blocks do BR Geo.

### 5.4 Monitoramento de vegetação de um talhão (`sathub-vegetation-monitoring`, 1.1.0)

Entradas: `areaId` (id da área no Satellite Hub), `index` e `days` (opcionais). O `index` aceita os
slugs de Sentinel-2 (`ndvi`, `evi`, `ndmi`...), Landsat 8/9 (`ndvi_landsat`, `ndmi_landsat`) e
Sentinel-1 (`rvi_s1`, radar, escala 0 a 4); capa e fontes dizem a coleção pelo
`vegetation.index.collectionName` (a 1.0.0 escrevia "Sentinel-2" fixo). Destaques (imagem mais
recente, média e faixa do período), o gráfico do índice, as quedas bruscas (`sathub-index-anomaly`,
opcional: quedas, situação da última imagem, gráfico e média do mesmo mês nos anos anteriores) e a
tabela das imagens. Avisa quando o índice está desabilitado no Satellite Hub (`indexEnabled`). Os dois
nós têm o valor fixo `minValidPercent = 60`.

### 5.5 Imagem de satélite mais recente (`car-latest-satellite-image`, 2.0.0)

Entradas: `codImovel` **ou** `area`. `report-area.geometry` vai para `sathub-latest-image`, com o
valor fixo `indices = ndvi,evi,ndmi,ndre`. Seções: capa (área, município do CAR ou ponto de
referência do polígono), cor verdadeira e um bloco por índice (mapa, barra de cores, estatísticas),
ou o aviso de sem imagem; cenas descartadas por nuvem e metodologia. Não usa o BR Geo.

### 5.6 Clima da área (`area-climate`, 1.1.0)

Chuva (totais, dias de chuva, maior sequência sem chuva, gráfico diário e, na 1.1.0, extremos: maior
chuva em 1 e em 5 dias, dias de 50 mm ou mais, veranicos de 10 e de 15 dias), ET0 do período,
temperatura (média, extremos, dias de 35 °C ou mais, noites de 3 °C ou menos, graus-dia, umidade,
vento, radiação, gráfico), **temperatura frente à média desde 2000** (`brweather-temperature-anomaly`:
anomalia da média, da máxima e da mínima, dias quentes e noites frias pelos percentis, tabela por ano)
e **balanço hídrico** (`brweather-water-balance`: água no solo no fim do período, dias de déficit,
déficit e excedente, gráfico). Período por `from`/`to` ou os últimos `days` (padrão 30), o mesmo para
todas as fontes; sem `from` nem `days`, o balanço usa os seus 90 dias padrão.

### 5.7 Janela de plantio (`area-planting-window`, 1.1.0)

Para `crop` (como o ZARC escreve): síntese (zoneada ou não, janelas da primeira combinação,
cultivares, solo predominante, umidade do solo hoje, chuva e ET0 de 30 dias, andamento da CONAB);
ZARC com a tabela de janelas por manejo, grupo e solo, a faixa de risco dos 36 decêndios (verde 20%,
amarelo 30%, laranja 40%) e as **cultivares indicadas** na UF (`brgeo-zarc-cultivars`, mesma cultura
e safra, até 15 linhas); **solo da área** (`brgeo-soil-units`: ordem principal, unidades, restrições;
`brgeo-soil-profiles`: argila, silte e areia de 0-40 cm dos perfis próximos, como apoio à escolha da
classe AD do ZARC); chuva com ET0 e extremos (rx1/rx5, dias de 50 mm, veranicos de 10 e 15 dias);
**umidade do solo** pelo balanço hídrico dos últimos 60 dias (`finalStoragePercent`, dias de déficit,
gráfico); temperatura dos últimos 30 dias; andamento da semeadura no estado; a **série histórica** da
cultura no município (`brgeo-crop-production-history` com `product = crop`, gráfico e tabela) e as
lavouras do município no último ano.

### 5.8 Acompanhamento de safra (`area-crop-monitoring`, 1.1.0)

Desde `from` (data de plantio) até `to` ou hoje: síntese, imagem mais recente com NDVI, NDMI e NDRE,
série do índice dos talhões do Satellite Hub na área (`sathub-index-series` com `from`/`to`; só há
série onde o Satellite Hub tem áreas cadastradas), **quedas bruscas do índice** desde o plantio
(`sathub-index-anomaly`: quantas, a última, situação atual, gráfico, média do mês nos anos
anteriores), **comparação entre satélites** (`sathub-index-compare`, índices padrão
`ndvi,ndvi_landsat,rvi_s1`: gráfico, tabela por índice, pares na mesma escala com viés e correlação;
aviso quando só uma coleção tem imagens), chuva com ET0 e extremos, **balanço hídrico desde o
plantio** (`brweather-water-balance` com `from` = plantio) e temperatura do período, andamento da
safra no estado.

### 5.9 Laudo de evento climático (`area-climate-event`, 1.1.0)

Para o período `from`–`to` de uma seca, estiagem, calor ou frio: o evento em números (chuva total e
diária, maior sequência sem chuva, veranicos de 10/15 dias, chuva extrema, extremos de temperatura,
anomalia de temperatura frente à média desde 2000, faixa do índice de vegetação, quedas do índice),
chuva diária com extremos (dias de 50 mm, rx1/rx5, veranicos) e ET0, temperatura diária e **o período
frente à média histórica** (`brweather-temperature-anomaly` com `from`/`to` do evento), série do
índice no período e **quedas bruscas do índice do início do evento até hoje** (`sathub-index-anomaly`
com `from` = início; a linha de base usa as imagens de antes do evento), imagem atual e, com `crop`, o
ZARC. A chuva não é comparada com a média histórica, e o laudo não atesta perda: é a base para quem
conclui.

### 5.10 Dossiê para crédito rural (`area-rural-credit-dossier`, 1.1.0)

Verificações (inscrição no CAR, Reserva Legal, sobreposições, imóvel removido do SICAR, área
declarada x calculada, pendências na carga, data da base SICAR; para polígono ou GIS, os imóveis do
CAR na área; embargo ativo, PRODES depois de 22/07/2008, DETER, cultura zoneada no ZARC, bioma, solo
com restrições, Censo Agro 2017), seguidas do detalhe: área, localização, CAR (histórico com variação
de área, data de referência por camada, pendências, hidrografia calculada, ou a tabela de imóveis do
CAR na área com a cobertura), embargos, DETER, PRODES, ZARC, **solo** (`brgeo-soil-units`), **Censo
Agro 2017** (`brgeo-census-evidence`, com o aviso "presença histórica, não prova"), imagem recente,
chuva de 12 meses, histórico produtivo do município (PAM) e andamento da safra (CONAB). Diz o que
**não** consulta (trabalho escravo, UCs, terras indígenas, bases estaduais).

### 5.11 Boletim agrícola do município (`municipality-agri-bulletin`, 1.1.0)

Para um código IBGE: lavouras do município (PAM) e, com `crop`, a **série histórica** da cultura
(`brgeo-crop-production-history` com `product = crop`), o ZARC da cultura e as **cultivares
indicadas** na UF (`brgeo-zarc-cultivars` pelo `municipalityIbge`, até 15 linhas); andamento da safra
no estado. A UF sai de `brgeo-crop-production.uf`; o nome do município, do ZARC, quando há cultura.
A série é da cultura informada: o `brgeo-crop-production` não expõe o produto principal como saída
para ligar no histórico.

### 5.12 Candidatos a talhão (`area-field-candidates`, 1.0.0)

Entradas: `codImovel` **ou** `area`. `report-area.geometry` vai para `sathub-latest-image` (valores
fixos `indices = ndvi`, `sizePx = 1024`, um bloco do SAM) e as saídas da imagem, mais a área e o
código do CAR, vão para `isgeosam-field-candidates` (nó opcional). Seções: capa com o aviso de que os
polígonos são propostas para revisão, a imagem e o resumo da segmentação, o mapa com os candidatos
numerados, a tabela (área, scores, pontos de atenção) e a metodologia. A primeira execução para uma
imagem nova costuma sair "em andamento"; a seguinte reaproveita o resultado guardado
([BLOCKS.md](BLOCKS.md), `isgeosam-field-candidates`). Precisa do is-geo-sam instalado e liberado na
instância ([INTEGRATIONS.md](INTEGRATIONS.md) §3).

### 5.13 Solo e aptidão agrícola (`area-soil-aptitude`, 1.0.0)

Entradas: CAR, polígono ou GIS, `crop` (como o ZARC escreve) e `season` opcional. Seções:

0. Síntese: solo predominante e restrições, argila de 0-40 cm do perfil mais próximo, cultura
   zoneada, cultivares, água no solo hoje e a cultura no município (último ano da PAM).
1. Área analisada.
2. Solos mapeados (`brgeo-soil-units`): ordens de solo, unidades (legenda, textura, relevo,
   pedregosidade/rochosidade) e restrições.
3. Perfis de solo próximos (`brgeo-soil-profiles`): classificação, drenagem, granulometria de 0-40 cm
   e os horizontes dos perfis com análise; nota de como usar na escolha da classe AD do ZARC.
4. Risco climático por classe de solo (`brgeo-zarc-risk`, `limit = 60`): tabela com a classe AD de
   cada linha em destaque (células `ad ad-AD1` a `ad-AD6` do `report-base.css`, do mais claro ao mais
   escuro) e a faixa de risco por decêndio.
5. Cultivares indicadas na UF (`brgeo-zarc-cultivars`, `limit = 20`).
6. Água no solo (`brweather-water-balance`, 90 dias, CAD padrão de 50 mm), com a nota de que a CAD
   real depende do solo.
7. A cultura no município (`brgeo-crop-production-history`, gráfico e tabela).
8. Metodologia, limitações e fontes.

### 5.14 Imóveis do CAR na área (`area-car-properties`, 1.0.0)

Entradas: polígono, GIS ou um código CAR (a área é a geometria do imóvel e a lista traz ele e os
vizinhos que se sobrepõem), e `limit` opcional (padrão 50, até 500). `car-in-area` é o nó principal
(não opcional); `car-data-reference` usa `uf = municipalities.mainUf` (opcional). Seções: síntese
(imóveis, cobertura, soma das partes, bioma, embargos ativos, data da base SICAR), área analisada,
tabela dos imóveis (município, situação, tipo, área declarada, parte na área, % da área, % do imóvel),
cobertura e sobreposições (a cobertura conta cada hectare uma vez; a soma das partes passa dela quando
há terra declarada por mais de um imóvel), embargos do IBAMA na área, data de referência da base SICAR
por camada e fontes.

### 5.15 Ideias (não decididas)

Possibilidades levantadas para conversar com o produto. **Nenhuma está decidida nem priorizada.**

- Laudo de conformidade do Código Florestal (déficit/excedente de Reserva Legal e APP pelo módulo
  fiscal e bioma), quando houver Blocks com as regras.
- Terras indígenas e unidades de conservação no dossiê de crédito, quando houver Blocks dessas bases.
- Relatórios com um script Python do Skills Lab (`skillslab-python`), como estimativa de
  produtividade, quando houver o workflow liberado.
- Chuva comparada com a média histórica (anomalia de chuva), quando o BR Weather guardar a climatologia
  do CHIRPS (a de temperatura já existe: `brweather-temperature-anomaly`).
- Comparativo entre versões do cadastro de um imóvel (o que mudou em geometria e camadas).
- Relatório de carteira: vários imóveis de um produtor/cliente com indicadores resumidos (depende
  de inputs em lista).
- Relatório de monitoramento de alertas DETER por período para um imóvel ou carteira.
