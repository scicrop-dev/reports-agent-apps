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
   espelhos do BLOCKS.md).
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
| 1 | `car-socioenvironmental` | Laudo socioambiental do imóvel CAR | `codImovel` | os 6 do CAR | **1.0.0**. Só CAR: roda numa instância sem o BR Geo |
| 2 | `car-socioenvironmental-complete` | Laudo socioambiental completo (imóvel CAR ou polígono) | CAR **ou** polígono, `crop?` | `report-area`, os 5 do CAR (opcionais), 6 do BR Geo, `brgeo-zarc-risk`, `brweather-rainfall`, `brweather-daily-weather`, `sathub-latest-image` | **2.0.0** |
| 3 | `brgeo-area-diagnosis` | Diagnóstico socioambiental de uma área | polígono | `brgeo-municipalities`, `brgeo-biomes`, `brgeo-embargoes`, `brgeo-deter-alerts`, `brgeo-prodes` | **1.0.0**. Só BR Geo |
| 4 | `sathub-vegetation-monitoring` | Monitoramento de vegetação de um talhão | `areaId` do Satellite Hub | `sathub-index-series` | **1.0.0** |
| 5 | `car-latest-satellite-image` | Imagem de satélite mais recente (imóvel CAR ou polígono) | CAR **ou** polígono | `report-area`, `car-property` (opcional), `sathub-latest-image` | **2.0.0**. Lê Sentinel-2 na hora (saída HTTPS para a AWS) |
| 6 | `area-climate` | Clima da área | CAR **ou** polígono, `from?`, `to?`, `days?` | `report-area`, `car-property`, `brgeo-municipalities`, `brweather-rainfall`, `brweather-daily-weather` | **1.0.0** |
| 7 | `area-planting-window` | Janela de plantio | CAR **ou** polígono, `crop`, `season?` | `report-area`, `car-property`, `brgeo-municipalities`, `brgeo-zarc-risk`, `brweather-*`, `brgeo-crop-progress`, `brgeo-crop-production` | **1.0.0** |
| 8 | `area-crop-monitoring` | Acompanhamento de safra | CAR **ou** polígono, `from` (plantio), `to?`, `crop?`, `index?` | `report-area`, `car-property`, `brgeo-municipalities`, `sathub-index-series`, `brweather-*`, `brgeo-crop-progress`, `sathub-latest-image` | **1.0.0** |
| 9 | `area-climate-event` | Laudo de evento climático | CAR **ou** polígono, `from`, `to`, `crop?`, `index?` | `report-area`, `car-property`, `brgeo-municipalities`, `brweather-*`, `sathub-index-series`, `brgeo-zarc-risk`, `sathub-latest-image` | **1.0.0** |
| 10 | `area-rural-credit-dossier` | Dossiê para crédito rural | CAR **ou** polígono, `crop`, `season?` | `report-area`, os 5 do CAR (opcionais), 5 do BR Geo, `brgeo-zarc-risk`, `brgeo-crop-production`, `brgeo-crop-progress`, `brweather-rainfall`, `sathub-latest-image` | **1.0.0** |
| 11 | `municipality-agri-bulletin` | Boletim agrícola do município | `municipalityIbge`, `crop?`, `season?` | `brgeo-crop-production`, `brgeo-crop-progress`, `brgeo-zarc-risk` | **1.0.0** |

Os prontos 2 a 11 usam o CSS comum `report-base.css` (o 1 mantém o seu). Um pronto só roda quando
**todos** os seus Blocks estão utilizáveis, inclusive os opcionais. Por isso os prontos só do CAR (1)
e só do BR Geo (3) continuam existindo: rodam onde só há uma das fontes.

**CAR ou polígono.** Os prontos 2 e 5 a 10 têm os inputs opcionais `codImovel`, `area` (geometry) e
`areaName`; quem roda preenche o código **ou** o polígono. O Block `report-area` resolve a área, os
Blocks do CAR são nós opcionais ligados a `area.codImovel` (pulados para polígono) e o template mostra
as seções do CAR em `{{#area.isCar}}`. Município e UF, para ZARC, CONAB e PAM, são os do município
com a maior parte da área (`municipalities.mainIbgeCode`/`mainUf`).

**Seções que dependem de rede ou de dado novo** (imagem, chuva, temperatura, série, ZARC, CONAB, PAM)
são nós opcionais: se a fonte falhar, o relatório sai com o aviso no lugar e a falha fica nos blocos
da execução. A imagem é sempre o último nó, para não gastar o tempo da execução antes dos outros.

### 5.1 Laudo socioambiental do imóvel CAR (`car-socioenvironmental`)

Entrada: `codImovel` (código do imóvel no SICAR). Seções:

0. Cabeçalho: título, código, emissão (`report.generatedAt`), execução (`report.executionId`; numa
   prévia aparece "Prévia, não registrada"), alerta se o imóvel foi removido do SICAR.
1. Identificação do imóvel (`car-property`).
2. Localização: mapa (`car-property-map`, imagem `data:` com legenda), município/UF, ponto de
   referência (lat/lon), área calculada × declarada (`car-property-geometry`).
3. Situação do cadastro: situação, condição, datas e histórico de versões (`car-history`).
4. Uso e cobertura declarados: destaques de Reserva Legal, vegetação nativa e APP (% e ha), aviso
   quando não há Reserva Legal declarada, tabela por camada com temas (`car-environmental-layers`).
5. Sobreposições com outros imóveis CAR (`car-overlaps`), ou "nenhuma sobreposição".
6. Fontes e limitações.

As seções do BR Geo ficaram num pronto à parte (§5.2), para este continuar rodando onde só há o CAR.

### 5.2 Laudo socioambiental completo (`car-socioenvironmental-complete`, 2.0.0)

Entradas: `codImovel` **ou** `area` (+ `areaName`), e `crop` opcional. Seções:

0. **Síntese**: área analisada, situação no CAR, Reserva Legal e sobreposições (só CAR), bioma,
   embargos ativos, DETER, PRODES, NDVI da imagem mais recente, chuva dos últimos 12 meses e, com
   `crop`, se a cultura é zoneada no ZARC. "Área não monitorada" aparece em cinza, nunca como "sem alerta".
1. Área analisada (dados do CAR ou do polígono, área, ponto, municípios).
2. Localização e bioma: mapa do CAR (`car-property-map`), biomas, caixa envolvente, municípios.
3. Cadastro Ambiental Rural: situação e histórico, uso e cobertura declarados, sobreposições. Para
   polígono, um aviso de que não se aplica.
4. a 6. Embargos do IBAMA, DETER, PRODES.
7. Imagem Sentinel-2 mais recente em cor verdadeira, com NDVI e NDMI.
8. e 9. Chuva (CHIRPS) e temperatura (NASA POWER) dos últimos 365 dias.
10. Perfil agrícola do município (PAM).
11. ZARC da cultura informada (ou como pedi-lo).
12. Fontes e limitações.

A 2.0.0 trocou o input `codImovel` obrigatório pelos três opcionais: cópias da 1.0.0 continuam como
estavam.

### 5.3 Diagnóstico socioambiental de uma área (`brgeo-area-diagnosis`)

Entradas: `area` (geometry, GeoJSON em SIRGAS 2000) e `areaName` (opcional). Mesmas seções do BR Geo
do laudo completo, para qualquer polígono (um talhão, um imóvel fora do CAR, uma área de compra).

### 5.4 Monitoramento de vegetação de um talhão (`sathub-vegetation-monitoring`)

Entradas: `areaId` (id da área no Satellite Hub), `index` e `days` (opcionais). Destaques (imagem
mais recente, média e faixa do período), o gráfico do índice e a tabela das imagens. O nó tem o valor
fixo `minValidPercent = 60`.

### 5.5 Imagem de satélite mais recente (`car-latest-satellite-image`, 2.0.0)

Entradas: `codImovel` **ou** `area`. `report-area.geometry` vai para `sathub-latest-image`, com o
valor fixo `indices = ndvi,evi,ndmi,ndre`. Seções: capa (área, município do CAR ou ponto de
referência do polígono), cor verdadeira e um bloco por índice (mapa, barra de cores, estatísticas),
ou o aviso de sem imagem; cenas descartadas por nuvem e metodologia. Não usa o BR Geo.

### 5.6 Clima da área (`area-climate`)

Chuva (totais, dias de chuva, maior sequência sem chuva, maior chuva, gráfico diário) e temperatura
(média, extremos, dias de 35 °C ou mais, noites de 3 °C ou menos, graus-dia, umidade, vento,
radiação, gráfico). Período por `from`/`to` ou os últimos `days` (padrão 30), o mesmo para as duas
fontes.

### 5.7 Janela de plantio (`area-planting-window`)

Para `crop` (como o ZARC escreve): síntese (zoneada ou não, janelas da primeira combinação, chuva
de 30 dias, andamento da CONAB), ZARC com a tabela de janelas por manejo, grupo e solo e a faixa
de risco dos 36 decêndios (verde 20%, amarelo 30%, laranja 40%), chuva e temperatura dos últimos 30
dias, andamento da semeadura no estado e a PAM do município.

### 5.8 Acompanhamento de safra (`area-crop-monitoring`)

Desde `from` (data de plantio) até `to` ou hoje: síntese, imagem mais recente com NDVI, NDMI e NDRE,
série do índice dos talhões do Satellite Hub na área (`sathub-index-series` com `from`/`to`; só há
série onde o Satellite Hub tem áreas cadastradas), chuva e temperatura do período e andamento da
safra no estado.

### 5.9 Laudo de evento climático (`area-climate-event`)

Para o período `from`–`to` de uma seca, estiagem, calor ou frio: o evento em números (chuva total e
diária, maior sequência sem chuva, extremos de temperatura, dias quentes, noites de geada, faixa do
índice de vegetação), chuva e temperatura diárias, série do índice no período, imagem atual e, com
`crop`, o ZARC. Não compara com a média histórica nem atesta perda: é a base para quem conclui.

### 5.10 Dossiê para crédito rural (`area-rural-credit-dossier`)

Verificações (inscrição no CAR, Reserva Legal, sobreposições, embargo ativo, PRODES depois de
22/07/2008, DETER, cultura zoneada no ZARC, bioma), seguidas do detalhe: área, localização, CAR,
embargos, DETER, PRODES, ZARC, imagem recente, chuva de 12 meses, histórico produtivo do município
(PAM) e andamento da safra (CONAB). Diz o que **não** consulta (trabalho escravo, UCs, terras
indígenas, bases estaduais).

### 5.11 Boletim agrícola do município (`municipality-agri-bulletin`)

Para um código IBGE: lavouras do município (PAM), ZARC da cultura (se informada) e andamento da
safra no estado. A UF sai de `brgeo-crop-production.uf`; o nome do município, do ZARC, quando há
cultura.

### 5.12 Ideias (não decididas)

Possibilidades levantadas para conversar com o produto. **Nenhuma está decidida nem priorizada.**

- Laudo de conformidade do Código Florestal (déficit/excedente de Reserva Legal e APP pelo módulo
  fiscal e bioma), quando houver Blocks com as regras.
- Terras indígenas e unidades de conservação no dossiê de crédito, quando houver Blocks dessas bases.
- Relatórios com um script Python do Skills Lab (`skillslab-python`), como estimativa de
  produtividade, quando houver o workflow liberado.
- Clima comparado com a média histórica (anomalia de chuva), quando o BR Weather guardar a climatologia.
- Comparativo entre versões do cadastro de um imóvel (o que mudou em geometria e camadas).
- Relatório de carteira: vários imóveis de um produtor/cliente com indicadores resumidos (depende
  de inputs em lista).
- Relatório de monitoramento de alertas DETER por período para um imóvel ou carteira.
