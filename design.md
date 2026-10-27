# Design dos Agent Apps do InfiniteStack

Guia de design e UX para construir Agent Apps que **parecem nativos** do InfiniteStack (IS). O [README.md](README.md) cobre o contrato técnico (empacotamento `.ispz`, manifest, datasource, build). Este documento cobre o que torna um app **coeso visualmente** com o ecossistema IS e o que é necessário criar para um novo app seguir essa identidade.

> Referências vivas: o `reports/` deste repositório e, no repositório `infinitestack-agent-apps`, os apps `recomendador-manejo` e `satellite-hub-viewer`. Quando em dúvida, copie deles.

---

## 1. Filosofia

Um Agent App é renderizado **dentro de um iframe** na seção *Agents → Apps* do IS. Isso define tudo:

1. **É um painel, não um site.** Não há barra de navegação própria, breadcrumbs, rodapé institucional, logo de empresa nem links "voltar". O IS já fornece a moldura. O app preenche o retângulo do iframe e nada mais.
2. **Uma tela, autossuficiente.** O fluxo principal acontece em `isp-index.html`. Evite navegação entre páginas; prefira painéis, abas e modais dentro da mesma tela.
3. **Tema escuro por padrão.** O IS é uma aplicação dark. Um app claro destoa imediatamente e "quebra" a sensação de continuidade.
4. **Denso e técnico.** O público é operacional/analítico. Fontes pequenas (10–14px), muita informação por área, rótulos em caixa-alta discretos — não um marketing landing page.
5. **Tudo embarcado.** Sem CDN, sem fonte externa, sem chamada de rede para terceiros. Todo CSS, JS e biblioteca vivem dentro do pacote (ver §7).

---

## 2. Tokens de cor

Todos os apps declaram a paleta como variáveis CSS em `:root`. Use **exatamente** estes tokens (nomes podem variar levemente entre `--surface`/`--panel`, mas os valores são o padrão IS):

```css
:root {
    --bg:       #0d0d0d;   /* fundo da página / iframe        */
    --surface:  #161616;   /* header, sidebar, cards          (alias: --panel)  */
    --surface2: #1e1e1e;   /* inputs, chips, blocos internos  (alias: --panel-2) */
    --border:   #2a2a2a;   /* divisórias e contornos          */
    --text:     #e8e8e8;   /* texto principal                 */
    --muted:    #6b6b6b;   /* rótulos, legendas, texto fraco  (~#8a909b ok)     */

    --accent:   #4ade80;   /* verde — ação primária, "saudável", sucesso        */
    --accent2:  #60a5fa;   /* azul — ação/realce secundário   (alias: --accent-2) */
    --danger:   #f87171;   /* erro, valor crítico             */
    --warn:     #facc15;   /* atenção, valor intermediário    */

    --radius:   8px;
    --font:     'Segoe UI', system-ui, -apple-system, Roboto, sans-serif;
}
```

Regras de uso:

- **Verde (`--accent`) é a cor da marca** e da ação principal (botão "Simular", "Buscar", "Treinar"). Use com moderação — um único ponto de verde por contexto guia o olho.
- **Azul (`--accent2`)** é realce secundário: badges informativos, hover de itens, links.
- Escalas semânticas (péssimo→excelente, baixo→alto) usam o gradiente `danger → #fb923c → warn → #86efac → accent`.
- Fundos sobem em camadas: `--bg` (mais fundo) → `--surface` → `--surface2` (mais à frente). Nunca use branco puro como fundo de bloco.

---

## 3. Tipografia

- Família única: stack de sistema (`Segoe UI`/`system-ui`). **Não importe Google Fonts** — viola o "sem CDN".
- Base do `body`: `font-size: 14px; line-height: 1.5;`.
- Escala observada:
  - Título do header (`h1`): **16px / 600**
  - Título de seção: **11px / 600**, `text-transform: uppercase`, `letter-spacing: 0.06–0.08em`, cor `--muted`
  - Rótulo de campo: **12px**, `--muted`
  - Corpo / inputs: **13px**
  - Valor numérico de destaque (cards): **22px / 700**
  - Legendas / unidades: **10–11px**, `--muted`
- Caixa-alta + `letter-spacing` é a assinatura visual dos títulos de seção. Use sempre.

---

## 4. Layout

### Estrutura base — header + corpo em grid, travado no viewport

O app ocupa 100% da altura do iframe e **não** rola a página inteira; cada painel rola internamente.

```css
body {
    display: grid;
    grid-template-rows: auto 1fr;   /* header + corpo                       */
    height: 100vh;
    overflow: hidden;               /* nada de scroll na página             */
}

.layout {                            /* corpo: sidebar de controles + área de conteúdo */
    display: grid;
    grid-template-columns: 320px 1fr;
    height: calc(100vh - 53px);      /* desconta a altura do header          */
    overflow: hidden;
}

.form-panel  { overflow-y: auto; }   /* sidebar rola sozinha                 */
.results-panel { overflow-y: auto; } /* conteúdo rola sozinho                */
```

Padrão recorrente: **sidebar de 320px** com os controles/entradas à esquerda, **área principal** (`1fr`) com resultados/mapa/tabela à direita. Apps mais ricos adicionam uma terceira linha (`grid-template-rows: auto 1fr auto`) para uma barra inferior (timeline, paginação).

### Header

```html
<header>
    <h1>Nome do App</h1>
    <span class="badge">v1.0</span>
    <div id="model-status">           <!-- empurrado para a direita com margin-left:auto -->
        <span class="dot ready"></span> Pronto
    </div>
</header>
```

- Fundo `--surface`, `border-bottom: 1px solid --border`, padding `~14px 24px`.
- À esquerda: título + badge opcional. À direita (via `margin-left:auto`): indicador de status com a "bolinha" (`.dot`/`.dot.ready` ganha glow verde).

---

## 5. Componentes

Catálogo do que os apps reaproveitam. Reuse estes; não invente variações.

| Componente | Características |
|---|---|
| **Campo de formulário** (`.field`) | `label` 12px muted em cima, `input`/`select` 100% largura, fundo `--surface2`, borda `--border`, `border-radius` 6–8px, `:focus` muda borda para `--accent`. |
| **Botão primário** (`.btn`) | Fundo `--accent`, texto escuro (`#0d1a0d`), 700, caixa-alta leve; `:hover` → `opacity .88`; `:disabled` → `opacity .35`. |
| **Botão secundário** | Fundo `--surface2`, borda `--border`, texto `--text`. |
| **Badge / chip** | Pílula (`border-radius: 10–20px`), 10–11px, fundo tonal do contexto (verde-escuro p/ ok, azul-escuro p/ info). |
| **Card de métrica** | Fundo `--surface`, borda, `border-radius`; barra de 3px no topo (`::before`) colorida pela escala semântica; valor 22px/700 + unidade 10px muted. |
| **Tabela** | Cabeçalho muted caixa-alta, linhas separadas por `--border`, fonte 12–13px, hover sutil. |
| **Modal** | `position: fixed; inset:0` com backdrop `rgba(0,0,0,.6)`; painel `--surface` com borda e `border-radius: 10px`; alterna `.open` para exibir (`display:flex`). |
| **Indicador de status** (`.dot`) | 7px círculo; `--muted` por padrão, vira `--accent` com `box-shadow` glow quando "ready". |
| **Loader** | Spinner CSS (`border` + `border-top-color: --accent` + `@keyframes spin`). Nunca GIF externo. |
| **Placeholder vazio** | Estado inicial centralizado, ícone SVG inline com `opacity: .25` + texto muted explicando o que fazer. |

Geometria geral: `border-radius` 6–8px, espaçamentos múltiplos de ~4px, divisórias sempre `1px solid var(--border)`.

---

## 6. Comportamento no iframe

- **Nunca** use `window.top`, `target="_blank"` para navegar o próprio app, nem redirecionamentos de página inteira. Tudo é XHR/`fetch` para a própria API do plugin.
- Chamadas vão para `BASE + '/api/...'`, onde `BASE` é o `pluginBasePath`. O proxy do IS resolve tudo na mesma origem — **não há CORS** e não deve haver URL absoluta com host.
- Injete o base path no JS via Thymeleaf inline (padrão canônico):

```html
<script th:inline="javascript">
    const BASE = /*[[${pluginBasePath}]]*/ '/api/plugins/meu-agent';
    fetch(BASE + '/api/model-status').then(r => r.json()).then(render);
</script>
```

  O comentário `/*[[...]]*/` é substituído pelo valor real em runtime e o literal serve de fallback no dev local.
- Estados longos (treino de modelo, carga de dados) → faça **polling** de um endpoint de status e reflita na bolinha do header; desabilite o botão de ação enquanto ocupado.

---

## 7. Regras de assets

Decorrem de `web.local_assets_only: true` no manifest:

1. **Sem CDN, sem origem externa.** Nada de `https://cdn...`, `fonts.googleapis.com`, etc. O IS pode rodar offline/air-gapped.
2. **CSS e JS da própria app vão inline** no `isp-index.html` (dentro de `<style>` e `<script th:inline>`). É aceitável e preferido para apps de uma tela — mantém o pacote simples.
3. **Bibliotecas de terceiros são vendoradas** em `src/main/resources/static/` e referenciadas pelo caminho do plugin:

   ```html
   <link rel="stylesheet" th:href="@{${pluginAssetsPath} + '/maplibre-gl.css'}">
   <script th:src="@{${pluginAssetsPath} + '/maplibre-gl.js'}"></script>
   ```

   (ver `satellite-hub-viewer`, que embarca MapLibre, geotiff, proj4 localmente.)
4. **Sempre** use `th:href`/`th:src` com `${pluginAssetsPath}` — nunca caminho absoluto. O `PluginWebConfig` mapeia `pluginBasePath + "/assets/**"` → `classpath:/static/`.
5. Ícones: SVG inline (preferido) ou um sprite local. Sem icon-font de CDN.

---

## 8. A página de status (`isp-status.html`)

Obrigatória, mínima, **não** precisa do tema completo — é uma página de diagnóstico, não de UX. Padrão:

```html
<body>
    <div class="kv"><span class="k">plugin:</span> meu-agent</div>
    <div class="kv"><span class="k">status:</span> <span th:text="${pluginStatus}">unknown</span></div>
    <div class="kv"><span class="k">path:</span>   <span th:text="${pluginPath}">—</span></div>
</body>
```

Fundo `#0d0d0d`, texto `#e8e8e8`, chave em `--muted`. Só isso.

---

## 9. O que é necessário criar para um novo app seguir a identidade

Checklist de design (complementa o checklist técnico do README):

- [ ] `:root` com os tokens de cor da §2 (copie do `reports/src/main/resources/templates/isp-index.html`).
- [ ] Tema **escuro**; nenhum fundo branco/claro de bloco.
- [ ] `body` em `grid` travado em `100vh` com `overflow: hidden`; scroll só nos painéis internos.
- [ ] **Header** com `--surface` + `border-bottom`, título 16px/600 e indicador de status à direita.
- [ ] Layout **sidebar 320px + conteúdo `1fr`** (ajuste conforme o app, mas mantenha o idioma visual).
- [ ] Títulos de seção em **caixa-alta 11px muted** com `letter-spacing`.
- [ ] Ação primária em **botão verde** (`--accent`); secundárias em `--surface2`.
- [ ] Inputs com fundo `--surface2`, borda `--border`, `:focus` verde.
- [ ] Fonte **somente system stack**; nenhuma fonte importada.
- [ ] **Zero CDN**: CSS/JS da app inline; libs de terceiros em `static/` via `pluginAssetsPath`.
- [ ] `const BASE = /*[[${pluginBasePath}]]*/ '...'` para todas as chamadas `fetch`.
- [ ] Nenhuma navegação de página inteira; modais/abas/painéis na mesma tela.
- [ ] Estados de carregamento com spinner CSS + bolinha de status; botões desabilitam enquanto ocupados.
- [ ] `isp-status.html` mínima no padrão da §8.
- [ ] Todos os templates com prefixo `isp-`.
- [ ] Texto da UI em **pt-BR** (`<html lang="pt-BR">`), coerente com os apps existentes.

> Atalho recomendado: comece copiando `isp-index.html` do app cuja estrutura mais se parece com o seu (`reports/` deste repositório; em `infinitestack-agent-apps`, `recomendador-manejo` para formulário→resultado e `satellite-hub-viewer` para mapa/visualização) e troque o conteúdo, preservando tokens, header e layout.
