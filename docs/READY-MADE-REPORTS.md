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
| `id` | kebab-case, único, igual ao nome do arquivo. Prefixo pela fonte principal (`car-`, `brgeo-`). |
| `version` | semver `x.y.z` (ver §4). |
| `name`, `description`, `category` | Texto do catálogo, no idioma do relatório (o laudo é em português). `name` vira o nome padrão da cópia (até 200 caracteres). |
| `inputs`, `flow`, `page` | Mesmo JSON que a API de versões usa (`InputDefinition`, `FlowDefinition`, `PageSettings`). Os ids dos nós são os namespaces do template. |
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
   `{{^lista}}` para a mensagem de lista vazia (o laudo faz assim).
5. Rode `mvn -q test`. O `ReadyMadeReportsValidityTest` pega **todos** os prontos e verifica Flow,
   template e página contra os Blocks, executa o Flow com saídas de exemplo e renderiza HTML e PDF em
   `reports/target/ready-made/` (abra o PDF e olhe). Se o pronto usa Blocks que ainda não têm
   duplos de teste, acrescente-os (hoje: `support/CarBlockContractStubs`, espelho do BLOCKS.md).
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

| # | Id | Nome | Blocks | Estado |
|---|---|---|---|---|
| 1 | `car-socioenvironmental` | Laudo socioambiental do imóvel CAR | `car-property`, `car-property-geometry`, `car-environmental-layers`, `car-overlaps`, `car-history`, `car-property-map` | **1.0.0**, aguardando os Blocks do CAR serem integrados e liberados |

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

Próximas versões: bioma, embargos IBAMA e alertas DETER/PRODES quando os Blocks `brgeo-*` existirem
(BLOCKS.md §3); viram seções novas (minor).

### 5.2 Ideias (não decididas)

Possibilidades levantadas para conversar com o produto. **Nenhuma está decidida nem priorizada.**

- Laudo de conformidade do Código Florestal (déficit/excedente de Reserva Legal e APP pelo módulo
  fiscal e bioma), quando houver Blocks com as regras.
- Relatório de due diligence socioambiental para crédito rural (CAR + embargos + desmatamento +
  terras indígenas/UCs).
- Comparativo entre versões do cadastro de um imóvel (o que mudou em geometria e camadas).
- Relatório de carteira: vários imóveis de um produtor/cliente com indicadores resumidos (depende
  de inputs em lista).
- Relatório de monitoramento de alertas DETER por período para um imóvel ou carteira.
