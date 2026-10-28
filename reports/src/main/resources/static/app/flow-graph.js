/*
 * The visual Flow editor: a palette of Blocks, a canvas where nodes are boxes and connections are
 * wires from an output (right dot) to an input (left dot), and an inspector with everything about the
 * selected node or Block (bindings, outputs as template variables, last run, documentation).
 *
 * It edits the same working model as the list view (flow-editor.js): [{id, block, bindings, position, optional}].
 * Port positions are computed from fixed row heights, not measured, so the wires are right even
 * while the tab is hidden. Colors follow the port type, so a wire can only join two ports of the
 * same color: that is the rule the engine applies too.
 */
(function () {
    'use strict';

    const { el, clear } = window.ReportsDom;
    const FlowEditor = window.ReportsFlowEditor;
    const SVG = 'http://www.w3.org/2000/svg';

    // Geometry of a node box (pixels at zoom 1); keep in sync with the .fg-* CSS.
    const NODE_W = 250;
    const BORDER = 1;
    const HEAD_H = 30;
    const META_H = 20;
    const LABEL_H = 18;
    const ROW_H = 22;
    const PAD_B = 6;
    const COLUMN_GAP = 330;
    const ROW_GAP = 28;
    const INPUTS = 'inputs';
    /** Nodes with more outputs than this show the connected ones and the first few, plus a "+N more" row. */
    const COLLAPSE_AFTER = 5;
    const ALWAYS_SHOWN = 3;

    const TYPE_COLORS = {
        string: '#60a5fa', number: '#c084fc', boolean: '#facc15', date: '#fb923c', datetime: '#fb923c',
        object: '#9ca3af', array: '#2dd4bf', geometry: '#4ade80'
    };

    /** What survives a re-render: camera, selection, palette filter, open panels. */
    const view = {
        x: 30, y: 30, scale: 1, fitPending: true, modelRef: null,
        selected: null, // {kind: 'node', id} | {kind: 'wire', to: 'node.port'} | {kind: 'block', id}
        query: '', fullscreen: false, help: false, inputsPos: { x: 20, y: 40 },
        expanded: new Set() // nodes showing all their outputs
    };

    // ------------------------------------------------------------------ model helpers

    function blocksById(ctx) {
        const out = {};
        ctx.blocks.forEach((b) => { out[b.definition.id] = b; });
        return out;
    }

    function inputsHeight(definition) {
        return LABEL_H + Math.max(1, definition.inputs.length) * ROW_H;
    }

    /**
     * The outputs a node box shows: all of them, or (for a big Block) the connected ones and the
     * first few. toggle tells whether a "+N more" / "show fewer" row follows.
     */
    function visibleOutputs(node, definition, model) {
        const all = definition.outputs;
        if (all.length <= COLLAPSE_AFTER) return { list: all, hidden: 0, toggle: false };
        if (view.expanded.has(node.id)) return { list: all, hidden: 0, toggle: true };
        const used = new Set();
        model.forEach((n) => Object.values(n.bindings).forEach((b) => { if (b.kind === 'node' && b.node === node.id) used.add(b.port); }));
        const list = all.filter((o, i) => i < ALWAYS_SHOWN || used.has(o.name));
        return { list, hidden: all.length - list.length, toggle: list.length < all.length };
    }

    function nodeHeight(definition, node, model) {
        if (!definition) return HEAD_H + META_H + ROW_H * 2 + PAD_B + 2 * BORDER;
        const outputs = visibleOutputs(node, definition, model);
        return 2 * BORDER + HEAD_H + META_H + inputsHeight(definition)
            + LABEL_H + Math.max(1, outputs.list.length) * ROW_H + (outputs.toggle ? ROW_H : 0) + PAD_B;
    }

    function heightOf(node, model, byId) {
        return nodeHeight(byId[node.block] && byId[node.block].definition, node, model);
    }

    function inputsNodeHeight(ctx) {
        return 2 * BORDER + HEAD_H + META_H + LABEL_H + Math.max(1, ctx.inputs.length) * ROW_H + PAD_B;
    }

    /** Canvas point of a port dot. kind: 'in' | 'out'. */
    function portPoint(model, ctx, byId, nodeId, kind, port) {
        if (nodeId === INPUTS) {
            const index = ctx.inputs.findIndex((i) => i.name === port);
            if (index < 0) return null;
            return { x: view.inputsPos.x + NODE_W, y: view.inputsPos.y + BORDER + HEAD_H + META_H + LABEL_H + index * ROW_H + ROW_H / 2 };
        }
        const node = model.find((n) => n.id === nodeId);
        const view_ = node && byId[node.block];
        if (!node || !view_ || !node.position) return null;
        const d = view_.definition;
        const base = node.position.y + BORDER + HEAD_H + META_H;
        if (kind === 'in') {
            const index = d.inputs.findIndex((p) => p.name === port);
            if (index < 0) return null;
            return { x: node.position.x, y: base + LABEL_H + index * ROW_H + ROW_H / 2 };
        }
        const index = visibleOutputs(node, d, model).list.findIndex((p) => p.name === port);
        if (index < 0) return null;
        return { x: node.position.x + NODE_W, y: base + inputsHeight(d) + LABEL_H + index * ROW_H + ROW_H / 2 };
    }

    /** Every wire of the model: {from: {node, port}, to: {node, port}, type}. */
    function wires(model, byId) {
        const out = [];
        model.forEach((node) => {
            const def = byId[node.block] && byId[node.block].definition;
            Object.entries(node.bindings).forEach(([port, b]) => {
                const target = def && def.inputs.find((p) => p.name === port);
                if (b.kind === 'input' && b.input) out.push({ from: { node: INPUTS, port: b.input }, to: { node: node.id, port }, type: target && target.type });
                if (b.kind === 'node' && b.node && b.port) out.push({ from: { node: b.node, port: b.port }, to: { node: node.id, port }, type: target && target.type });
            });
        });
        return out;
    }

    /** Ids of the nodes {@code nodeId} depends on, directly or not. */
    function ancestors(model, nodeId, seen) {
        const acc = seen || new Set();
        const node = model.find((n) => n.id === nodeId);
        if (!node) return acc;
        Object.values(node.bindings).forEach((b) => {
            if (b.kind === 'node' && b.node && !acc.has(b.node)) {
                acc.add(b.node);
                ancestors(model, b.node, acc);
            }
        });
        return acc;
    }

    /** Execution order as the engine computes it: Kahn's algorithm keeping the declared order. */
    function executionOrder(model) {
        const ids = model.map((n) => n.id);
        const deps = {};
        model.forEach((n) => {
            deps[n.id] = new Set(Object.values(n.bindings).filter((b) => b.kind === 'node' && ids.includes(b.node)).map((b) => b.node));
        });
        const order = [];
        const done = new Set();
        let progress = true;
        while (progress && order.length < ids.length) {
            progress = false;
            for (const id of ids) {
                if (done.has(id)) continue;
                if ([...deps[id]].every((d) => done.has(d))) {
                    order.push(id);
                    done.add(id);
                    progress = true;
                    break;
                }
            }
        }
        return order; // shorter than the model when there is a cycle
    }

    /** Problems the person can fix on the canvas; the server validation stays the reference. */
    function problems(model, ctx, byId) {
        const list = [];
        model.forEach((node) => {
            const v = byId[node.block];
            if (!v) { list.push({ node: node.id, text: 'block ' + node.block + ' is not available to you' }); return; }
            if (!v.usable) list.push({ node: node.id, text: v.definition.name + ' is not released or has no data yet' });
            v.definition.inputs.filter((p) => p.required && !node.bindings[p.name]).forEach((p) => {
                list.push({ node: node.id, port: p.name, text: node.id + '.' + p.name + ' is required and not connected' });
            });
        });
        if (executionOrder(model).length < model.length) list.push({ text: 'the flow has a cycle' });
        return list;
    }

    /** Positions for nodes that have none: one column per dependency depth, left to right. */
    function autoLayout(model, ctx, byId, all) {
        const depth = {};
        const order = executionOrder(model);
        order.forEach((id) => {
            const node = model.find((n) => n.id === id);
            const parents = Object.values(node.bindings).filter((b) => b.kind === 'node').map((b) => depth[b.node] || 0);
            depth[id] = parents.length ? Math.max(...parents) + 1 : 0;
        });
        const columns = {};
        model.forEach((node) => {
            const d = depth[node.id] || 0;
            (columns[d] = columns[d] || []).push(node);
        });
        view.inputsPos = { x: 20, y: 40 };
        Object.entries(columns).forEach(([d, nodes]) => {
            let y = 40;
            nodes.forEach((node) => {
                if (all || !node.position) node.position = { x: 20 + COLUMN_GAP * (Number(d) + 1), y };
                y = (all ? y : Math.max(y, node.position.y)) + heightOf(node, model, byId) + ROW_GAP;
            });
        });
    }

    function compatible(sourceType, port) {
        return sourceType && port && sourceType === port.type;
    }

    /**
     * Connects the obvious inputs of a node just added: a required input with exactly one source of
     * the same name and type (or, failing that, exactly one source of the same type).
     */
    function autoWire(node, model, ctx, byId) {
        const def = byId[node.block].definition;
        def.inputs.filter((p) => p.required).forEach((port) => {
            const sources = [];
            ctx.inputs.filter((i) => i.type === port.type).forEach((i) => sources.push({ binding: { kind: 'input', input: i.name }, name: i.name }));
            model.filter((n) => n !== node && byId[n.block]).forEach((n) => {
                byId[n.block].definition.outputs.filter((o) => o.type === port.type)
                    .forEach((o) => sources.push({ binding: { kind: 'node', node: n.id, port: o.name }, name: o.name }));
            });
            const sameName = sources.filter((s) => s.name === port.name);
            const pick = sameName.length === 1 ? sameName[0] : sources.length === 1 ? sources[0] : null;
            if (pick) node.bindings[port.name] = pick.binding;
        });
    }

    function addNode(blockId, model, ctx, byId, position) {
        const node = { id: FlowEditor.nextNodeId(model, blockId), block: blockId, bindings: {}, position };
        if (!node.position) {
            const maxY = model.reduce((m, n) => Math.max(m, n.position ? n.position.y + heightOf(n, model, byId) : 0), 0);
            node.position = { x: 20 + COLUMN_GAP, y: maxY + ROW_GAP };
        }
        model.push(node);
        autoWire(node, model, ctx, byId);
        view.selected = { kind: 'node', id: node.id };
        ctx.onChange();
    }

    function removeNode(id, model, ctx) {
        const index = model.findIndex((n) => n.id === id);
        if (index < 0) return;
        model.splice(index, 1);
        model.forEach((n) => Object.keys(n.bindings).forEach((p) => {
            if (n.bindings[p].kind === 'node' && n.bindings[p].node === id) delete n.bindings[p];
        }));
        view.selected = null;
        ctx.onChange();
    }

    function removeWire(to, model, ctx) {
        const [nodeId, port] = to.split('.');
        const node = model.find((n) => n.id === nodeId);
        if (node) delete node.bindings[port];
        view.selected = null;
        ctx.onChange();
    }

    // ------------------------------------------------------------------ render

    /**
     * ctx as in flow-editor.js. toggle is the Graph | List switch, shown in the toolbar.
     */
    function render(container, model, ctx, toggle) {
        clear(container);
        const byId = blocksById(ctx);
        if (view.modelRef !== model) {
            // Another version or report: start from its own layout.
            view.modelRef = model;
            view.selected = null;
            view.fitPending = true;
            if (model.some((n) => !n.position)) autoLayout(model, ctx, byId, model.every((n) => !n.position));
            const minX = Math.min(...model.map((n) => n.position.x), 400);
            const minY = Math.min(...model.map((n) => n.position.y), 100);
            view.inputsPos = { x: Math.max(-200, minX - COLUMN_GAP), y: Math.max(20, minY) };
        } else if (model.some((n) => !n.position)) {
            autoLayout(model, ctx, byId, false);
        }

        const root = el('div.fg' + (view.fullscreen ? '.fg-fullscreen' : ''));
        root.appendChild(toolbar(root, container, model, ctx, byId, toggle));
        const body = el('div.fg-body');
        body.appendChild(palette(container, model, ctx, byId));
        const stage = el('div.fg-stage', { tabindex: '0' });
        body.appendChild(stage);
        const inspector = el('div.fg-inspector');
        body.appendChild(inspector);
        root.appendChild(body);
        container.appendChild(root);

        const canvas = new Canvas(stage, inspector, container, model, ctx, byId);
        canvas.draw();
        renderInspector(inspector, container, model, ctx, byId);
        if (view.help) root.appendChild(helpPanel(container, model, ctx));
    }

    function rerender(container, model, ctx) {
        // The Graph | List switch lives in flow-editor.js: ask it to render the whole tab again.
        FlowEditor.render(container.parentNode, model, ctx);
    }

    function toolbar(root, container, model, ctx, byId, toggle) {
        const zoom = (factor) => { view.scale = clamp(view.scale * factor, 0.35, 1.8); rerender(container, model, ctx); };
        const order = executionOrder(model);
        const issues = problems(model, ctx, byId);
        return el('div.fg-toolbar', null,
            toggle,
            el('span.fg-sep'),
            ctx.readOnly ? null : el('button.icon', { title: 'Put every node in columns by dependency', onclick: () => { autoLayout(model, ctx, byId, true); view.fitPending = true; ctx.onDirty(); rerender(container, model, ctx); } }, 'Auto layout'),
            el('button.icon', { title: 'Show the whole flow', onclick: () => { view.fitPending = true; rerender(container, model, ctx); } }, 'Fit'),
            el('button.icon', { title: 'Zoom out', onclick: () => zoom(1 / 1.2) }, '-'),
            el('span.fg-zoom', null, Math.round(view.scale * 100) + '%'),
            el('button.icon', { title: 'Zoom in', onclick: () => zoom(1.2) }, '+'),
            el('button.icon', { title: 'Use the whole window', onclick: () => { view.fullscreen = !view.fullscreen; view.fitPending = true; rerender(container, model, ctx); } },
                view.fullscreen ? 'Exit full screen' : 'Full screen'),
            el('span.fg-status', null,
                el('span.chip', null, model.length + ' node(s)'),
                order.length === model.length ? null : el('span.chip.error', null, 'cycle'),
                issues.length
                    ? el('span.chip.warn', { title: issues.map((i) => i.text).join('\n') }, issues.length + ' to fix')
                    : (model.length ? el('span.chip.ok', null, 'ready to run') : null),
                ctx.readOnly ? el('span.chip', { title: 'Published versions do not change: create a new draft to edit' }, 'read-only') : null),
            el('button.btn-secondary.fg-help-btn', { onclick: () => { view.help = !view.help; rerender(container, model, ctx); } }, 'How flows work'));
    }

    // ------------------------------------------------------------------ palette

    function palette(container, model, ctx, byId) {
        const box = el('div.fg-palette');
        const search = el('input', {
            placeholder: 'Search blocks...', value: view.query,
            oninput: (e) => { view.query = e.target.value; fill(); }
        });
        const list = el('div.fg-palette-list');
        box.appendChild(el('div.fg-palette-head', null, el('div.editor-label', null, 'Blocks'), search));
        box.appendChild(list);
        box.appendChild(el('div.fg-palette-foot', null,
            ctx.readOnly ? 'Read-only version: browse the blocks, editing needs a draft.'
                : 'Drag a block to the canvas, or double-click it. Click to read about it.'));

        function fill() {
            clear(list);
            const q = view.query.trim().toLowerCase();
            const groups = {};
            ctx.blocks.forEach((v) => {
                const d = v.definition;
                const text = (d.id + ' ' + d.name + ' ' + (d.description || '') + ' ' + ((v.docs && v.docs.summary) || '')).toLowerCase();
                if (q && !text.includes(q)) return;
                const category = (v.docs && v.docs.category) || 'Other';
                (groups[category] = groups[category] || []).push(v);
            });
            const names = Object.keys(groups).sort();
            if (!names.length) list.appendChild(el('div.muted', null, ctx.blocks.length ? 'No block matches.' : 'No block is available to you yet.'));
            names.forEach((category) => {
                list.appendChild(el('div.fg-group', null, category));
                groups[category].forEach((v) => list.appendChild(paletteCard(v)));
            });
        }

        function paletteCard(v) {
            const d = v.definition;
            const canAdd = !ctx.readOnly && v.usable;
            const selected = view.selected && view.selected.kind === 'block' && view.selected.id === d.id;
            const card = el('div.fg-card' + (selected ? '.selected' : '') + (v.usable ? '' : '.unusable'), {
                draggable: canAdd ? 'true' : null,
                title: canAdd ? 'Drag to the canvas' : (v.usable ? '' : 'Not released or without data: it cannot be used yet'),
                onclick: () => { view.selected = { kind: 'block', id: d.id }; rerender(container, model, ctx); },
                ondblclick: () => { if (canAdd) addNode(d.id, model, ctx, byId, null); },
                ondragstart: (e) => { e.dataTransfer.setData('text/x-reports-block', d.id); e.dataTransfer.effectAllowed = 'copy'; }
            },
                el('div.fg-card-title', null, d.name, v.usable ? null : el('span.chip.warn', null, 'unusable')),
                el('div.fg-card-summary', null, (v.docs && v.docs.summary) || d.description || ''),
                el('div.fg-card-ports', null,
                    d.inputs.map((p) => typeDot(p.type, 'in: ' + p.name + ' (' + p.type + ')')),
                    el('span.fg-arrow', null, 'to'),
                    d.outputs.map((p) => typeDot(p.type, 'out: ' + p.name + ' (' + p.type + ')'))));
            return card;
        }

        fill();
        return box;
    }

    function typeDot(type, title) {
        return el('span.fg-type-dot', { style: 'background:' + (TYPE_COLORS[type] || '#888'), title: title || type });
    }

    // ------------------------------------------------------------------ canvas

    class Canvas {
        constructor(stage, inspector, container, model, ctx, byId) {
            this.stage = stage;
            this.inspector = inspector;
            this.container = container;
            this.model = model;
            this.ctx = ctx;
            this.byId = byId;
            this.world = el('div.fg-world');
            this.svg = document.createElementNS(SVG, 'svg');
            this.svg.setAttribute('class', 'fg-wires');
            this.world.appendChild(this.svg);
            stage.appendChild(this.world);
            this.bindStage();
        }

        draw() {
            const { model, ctx, byId } = this;
            this.world.appendChild(this.inputsNode());
            model.forEach((node) => this.world.appendChild(this.nodeBox(node)));
            this.drawWires();
            this.applyCamera();
            if (!model.length) {
                this.stage.appendChild(el('div.fg-empty', null,
                    el('strong', null, ctx.readOnly ? 'This version has no node.' : 'Start your flow'),
                    el('div', null, '1. Declare what the reader types in the Inputs tab (e.g. codImovel).'),
                    el('div', null, '2. Drag a block from the left (or double-click it).'),
                    el('div', null, '3. Drag from an output dot (right) to an input dot (left) of the same color.'),
                    el('div', null, '4. Use the outputs in the Template tab as {{node.output}}.')));
            }
            // Fit once the stage has a size (the tab may be hidden at render time).
            const fitWhenVisible = () => {
                if (!view.fitPending || !this.stage.clientWidth) return false;
                this.fit();
                return true;
            };
            if (!fitWhenVisible() && window.ResizeObserver) {
                const observer = new ResizeObserver(() => { if (fitWhenVisible()) observer.disconnect(); });
                observer.observe(this.stage);
            }
        }

        applyCamera() {
            this.world.style.transform = 'translate(' + view.x + 'px,' + view.y + 'px) scale(' + view.scale + ')';
            this.stage.style.backgroundPosition = view.x + 'px ' + view.y + 'px';
            this.stage.style.backgroundSize = (24 * view.scale) + 'px ' + (24 * view.scale) + 'px';
        }

        fit() {
            const { model, ctx, byId } = this;
            const boxes = [{ x: view.inputsPos.x, y: view.inputsPos.y, w: NODE_W, h: inputsNodeHeight(ctx) }];
            model.forEach((n) => boxes.push({ x: n.position.x, y: n.position.y, w: NODE_W, h: heightOf(n, model, byId) }));
            const minX = Math.min(...boxes.map((b) => b.x)) - 30;
            const minY = Math.min(...boxes.map((b) => b.y)) - 30;
            const maxX = Math.max(...boxes.map((b) => b.x + b.w)) + 30;
            const maxY = Math.max(...boxes.map((b) => b.y + b.h)) + 30;
            const w = this.stage.clientWidth;
            const h = this.stage.clientHeight;
            view.scale = clamp(Math.min(w / (maxX - minX), h / (maxY - minY)), 0.35, 1.1);
            view.x = (w - (maxX - minX) * view.scale) / 2 - minX * view.scale;
            view.y = (h - (maxY - minY) * view.scale) / 2 - minY * view.scale;
            view.fitPending = false;
            this.applyCamera();
            const zoomLabel = this.container.querySelector('.fg-zoom');
            if (zoomLabel) zoomLabel.textContent = Math.round(view.scale * 100) + '%';
        }

        /** Canvas coordinates of a mouse event. */
        toWorld(event) {
            const rect = this.stage.getBoundingClientRect();
            return { x: (event.clientX - rect.left - view.x) / view.scale, y: (event.clientY - rect.top - view.y) / view.scale };
        }

        inputsNode() {
            const { ctx } = this;
            const box = el('div.fg-node.fg-inputs', { style: this.place(view.inputsPos), 'data-drag': INPUTS },
                el('div.fg-head', null, el('span.fg-title', null, 'Report inputs')),
                el('div.fg-meta', null, 'what the reader types when running'),
                el('div.fg-label', null, 'Outputs'),
                ctx.inputs.length ? ctx.inputs.map((input) => el('div.fg-port.out', { title: (input.label || input.name) + ' (' + input.type + ')' },
                    el('span.fg-port-name', null, input.name), el('span.fg-port-type', null, input.type),
                    this.dot('out', INPUTS, input.name, input.type)))
                    : el('div.fg-port.fg-port-empty', null, 'none: add them in the Inputs tab'));
            return box;
        }

        nodeBox(node) {
            const { model, ctx, byId } = this;
            const v = byId[node.block];
            const d = v && v.definition;
            const order = executionOrder(model).indexOf(node.id) + 1;
            const selected = view.selected && view.selected.kind === 'node' && view.selected.id === node.id;
            const run = lastRunOf(ctx, node.id);
            const unbound = d ? d.inputs.filter((p) => p.required && !node.bindings[p.name]).length : 0;
            const classes = 'div.fg-node' + (selected ? '.selected' : '') + (!v ? '.missing' : !v.usable ? '.unusable' : '')
                + (unbound ? '.incomplete' : '') + (run ? (run.status === 'COMPLETED' ? '.ran-ok' : run.status === 'SKIPPED' ? '.ran-skipped' : '.ran-failed') : '');
            const box = el(classes, { style: this.place(node.position), 'data-node': node.id, 'data-drag': node.id },
                el('div.fg-head', { title: d ? d.description : node.block },
                    el('span.fg-order', { title: 'Execution order' }, order ? String(order) : '!'),
                    el('span.fg-title', null, d ? d.name : node.block),
                    run ? el('span.fg-run.' + (run.status === 'COMPLETED' ? 'ok' : run.status === 'SKIPPED' ? 'skipped' : 'failed'),
                        { title: run.status + (run.error ? ': ' + run.error : '') },
                        run.status === 'COMPLETED' ? run.durationMs + ' ms' : run.status === 'SKIPPED' ? 'skipped' : 'failed') : null),
                el('div.fg-meta', null, el('code', null, node.id), ' ', d ? d.id + ' ' + d.version : 'unavailable', node.optional ? ' · optional' : ''));
            if (!d) {
                box.appendChild(el('div.fg-port.fg-port-empty', null, 'Block not available to you'));
                box.appendChild(el('div.fg-port.fg-port-empty', null, 'Release it to run this flow'));
                return box;
            }
            box.appendChild(el('div.fg-label', null, 'Inputs'));
            if (!d.inputs.length) box.appendChild(el('div.fg-port.fg-port-empty', null, 'no input'));
            d.inputs.forEach((port) => {
                const b = node.bindings[port.name];
                const fixed = b && b.kind === 'fixed';
                box.appendChild(el('div.fg-port.in' + (port.required && !b ? '.missing' : ''), { title: port.description || '' },
                    this.dot('in', node.id, port.name, port.type, !!b && !fixed),
                    el('span.fg-port-name', null, port.name, port.required ? el('span.req', null, '*') : null),
                    fixed ? el('span.fg-fixed', { title: 'fixed value' }, '= ' + short(b.value)) : el('span.fg-port-type', null, port.type)));
            });
            box.appendChild(el('div.fg-label', null, 'Outputs'));
            if (!d.outputs.length) box.appendChild(el('div.fg-port.fg-port-empty', null, 'no output'));
            const outputs = visibleOutputs(node, d, model);
            outputs.list.forEach((port) => {
                box.appendChild(el('div.fg-port.out', { title: port.description || '' },
                    el('span.fg-port-name', null, port.name), el('span.fg-port-type', null, port.type),
                    this.dot('out', node.id, port.name, port.type)));
            });
            if (outputs.toggle) {
                box.appendChild(el('div.fg-port.fg-more', {
                    'data-toggle': node.id,
                    title: outputs.hidden ? 'Show every output of this block' : 'Show only the connected outputs'
                }, outputs.hidden ? '+ ' + outputs.hidden + ' more outputs' : 'show fewer outputs'));
            }
            return box;
        }

        place(position) {
            return 'left:' + position.x + 'px;top:' + position.y + 'px;width:' + NODE_W + 'px';
        }

        dot(kind, nodeId, port, type, bound) {
            return el('span.fg-dot.' + kind + (bound ? '.bound' : ''), {
                style: '--dot:' + (TYPE_COLORS[type] || '#888'),
                'data-port': kind + ':' + nodeId + '.' + port,
                'data-type': type,
                title: kind === 'out' ? 'Drag to an input of type ' + type : (bound ? 'Drag to reconnect' : 'Connect an output of type ' + type)
            });
        }

        drawWires() {
            const { model, ctx, byId, svg } = this;
            while (svg.firstChild) svg.removeChild(svg.firstChild);
            wires(model, byId).forEach((w) => {
                const a = portPoint(model, ctx, byId, w.from.node, 'out', w.from.port);
                const b = portPoint(model, ctx, byId, w.to.node, 'in', w.to.port);
                if (!a || !b) return;
                const key = w.to.node + '.' + w.to.port;
                const selected = view.selected && view.selected.kind === 'wire' && view.selected.to === key;
                const d = curve(a, b);
                const hit = path(d, 'fg-wire-hit');
                hit.addEventListener('pointerdown', (e) => e.stopPropagation());
                hit.addEventListener('click', (e) => {
                    e.stopPropagation();
                    view.selected = { kind: 'wire', to: key };
                    rerender(this.container, model, ctx);
                });
                const title = document.createElementNS(SVG, 'title');
                title.textContent = w.from.node + '.' + w.from.port + ' to ' + key;
                hit.appendChild(title);
                svg.appendChild(hit);
                const line = path(d, 'fg-wire' + (selected ? ' selected' : ''));
                line.style.stroke = TYPE_COLORS[w.type] || '#888';
                svg.appendChild(line);
            });
        }

        bindStage() {
            const stage = this.stage;
            stage.addEventListener('wheel', (e) => {
                e.preventDefault();
                const rect = stage.getBoundingClientRect();
                const px = e.clientX - rect.left;
                const py = e.clientY - rect.top;
                const next = clamp(view.scale * (e.deltaY < 0 ? 1.1 : 1 / 1.1), 0.35, 1.8);
                view.x = px - (px - view.x) * (next / view.scale);
                view.y = py - (py - view.y) * (next / view.scale);
                view.scale = next;
                this.applyCamera();
                const zoomLabel = this.container.querySelector('.fg-zoom');
                if (zoomLabel) zoomLabel.textContent = Math.round(view.scale * 100) + '%';
            }, { passive: false });

            stage.addEventListener('pointerdown', (e) => this.onPointerDown(e));
            stage.addEventListener('keydown', (e) => {
                if (this.ctx.readOnly || !view.selected) return;
                if (e.key !== 'Delete' && e.key !== 'Backspace') return;
                const selected = view.selected;
                if (selected.kind === 'node') removeNode(selected.id, this.model, this.ctx);
                else if (selected.kind === 'wire') removeWire(selected.to, this.model, this.ctx);
                e.preventDefault();
            });
            stage.addEventListener('dragover', (e) => {
                if (!this.ctx.readOnly && e.dataTransfer.types.includes('text/x-reports-block')) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }
            });
            stage.addEventListener('drop', (e) => {
                const blockId = e.dataTransfer.getData('text/x-reports-block');
                if (!blockId || this.ctx.readOnly) return;
                e.preventDefault();
                const p = this.toWorld(e);
                addNode(blockId, this.model, this.ctx, this.byId, { x: p.x - NODE_W / 2, y: p.y - HEAD_H / 2 });
            });
        }

        onPointerDown(e) {
            if (e.button !== 0) return;
            const target = e.target;
            const portSpec = target.getAttribute && target.getAttribute('data-port');
            if (portSpec && !this.ctx.readOnly) {
                e.preventDefault();
                this.startConnect(e, portSpec);
                return;
            }
            const toggle = target.getAttribute && target.getAttribute('data-toggle');
            if (toggle) {
                e.preventDefault();
                this.toggleOutputs(toggle);
                return;
            }
            const handle = target.closest && target.closest('[data-drag]');
            if (handle) {
                e.preventDefault();
                this.startDrag(e, handle.getAttribute('data-drag'));
                return;
            }
            this.startPan(e);
        }

        track(e, onMove, onUp) {
            const stage = this.stage;
            stage.setPointerCapture(e.pointerId);
            const move = (ev) => onMove(ev);
            const up = (ev) => {
                stage.removeEventListener('pointermove', move);
                stage.removeEventListener('pointerup', up);
                stage.removeEventListener('pointercancel', up);
                try { stage.releasePointerCapture(e.pointerId); } catch (ignored) { /* already released */ }
                onUp(ev);
            };
            stage.addEventListener('pointermove', move);
            stage.addEventListener('pointerup', up);
            stage.addEventListener('pointercancel', up);
        }

        /** Shows all (or fewer) outputs of a node and moves the boxes below it so they do not overlap. */
        toggleOutputs(nodeId) {
            const { model, byId } = this;
            const node = model.find((n) => n.id === nodeId);
            const before = heightOf(node, model, byId);
            if (view.expanded.has(nodeId)) view.expanded.delete(nodeId); else view.expanded.add(nodeId);
            const delta = heightOf(node, model, byId) - before;
            if (!this.ctx.readOnly) {
                model.forEach((other) => {
                    if (other !== node && Math.abs(other.position.x - node.position.x) < NODE_W && other.position.y > node.position.y) {
                        other.position.y += delta;
                    }
                });
                this.ctx.onDirty();
            }
            rerender(this.container, model, this.ctx);
        }

        startPan(e) {
            const start = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
            let moved = false;
            this.stage.classList.add('panning');
            this.track(e, (ev) => {
                const dx = ev.clientX - start.x;
                const dy = ev.clientY - start.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
                view.x = start.vx + dx;
                view.y = start.vy + dy;
                this.applyCamera();
            }, () => {
                this.stage.classList.remove('panning');
                this.stage.focus();
                if (!moved && view.selected) {
                    view.selected = null;
                    rerender(this.container, this.model, this.ctx);
                }
            });
        }

        startDrag(e, nodeId) {
            const { model } = this;
            const node = nodeId === INPUTS ? null : model.find((n) => n.id === nodeId);
            const position = node ? node.position : view.inputsPos;
            const start = { x: e.clientX, y: e.clientY, px: position.x, py: position.y };
            const box = nodeId === INPUTS ? this.world.querySelector('.fg-inputs') : this.world.querySelector('[data-node="' + cssEscape(nodeId) + '"]');
            let moved = false;
            this.track(e, (ev) => {
                if (this.ctx.readOnly && node) return;
                const dx = (ev.clientX - start.x) / view.scale;
                const dy = (ev.clientY - start.y) / view.scale;
                if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
                position.x = Math.round(start.px + dx);
                position.y = Math.round(start.py + dy);
                if (box) { box.style.left = position.x + 'px'; box.style.top = position.y + 'px'; }
                this.drawWires();
            }, () => {
                if (moved && node) this.ctx.onDirty();
                if (!moved && node) {
                    view.selected = { kind: 'node', id: node.id };
                    rerender(this.container, model, this.ctx);
                }
            });
        }

        /** Drag a wire from an output to an input; starting on a bound input picks its wire up. */
        startConnect(e, spec) {
            const { model, ctx, byId } = this;
            const [kind, ref] = [spec.slice(0, spec.indexOf(':')), spec.slice(spec.indexOf(':') + 1)];
            let [fromNode, fromPort] = ref.split('.');
            let type = e.target.getAttribute('data-type');
            if (kind === 'in') {
                const node = model.find((n) => n.id === fromNode);
                const b = node && node.bindings[fromPort];
                if (!b || b.kind === 'fixed') return; // nothing to pick up: inputs are reached from outputs
                delete node.bindings[fromPort];
                if (b.kind === 'input') { fromNode = INPUTS; fromPort = b.input; } else { fromNode = b.node; fromPort = b.port; }
                this.drawWires();
            }
            const start = portPoint(model, ctx, byId, fromNode, 'out', fromPort);
            if (!start) return;
            const temp = path('', 'fg-wire temp');
            temp.style.stroke = TYPE_COLORS[type] || '#888';
            this.svg.appendChild(temp);

            // Which inputs may receive this wire: same type, not the source node, no cycle.
            const valid = (nodeId, portName) => {
                if (fromNode !== INPUTS && (nodeId === fromNode || ancestors(model, fromNode).has(nodeId))) return false;
                const node = model.find((n) => n.id === nodeId);
                const v = node && byId[node.block];
                const port = v && v.definition.inputs.find((p) => p.name === portName);
                return compatible(type, port);
            };
            this.world.querySelectorAll('.fg-dot.in').forEach((dot) => {
                const [n, p] = dot.getAttribute('data-port').slice(3).split('.');
                dot.classList.add(valid(n, p) ? 'target-ok' : 'target-no');
            });
            this.stage.classList.add('connecting');

            this.track(e, (ev) => {
                temp.setAttribute('d', curve(start, this.toWorld(ev)));
            }, (ev) => {
                temp.remove();
                this.stage.classList.remove('connecting');
                const under = document.elementFromPoint(ev.clientX, ev.clientY);
                const dotSpec = under && under.getAttribute && under.getAttribute('data-port');
                if (dotSpec && dotSpec.startsWith('in:')) {
                    const [n, p] = dotSpec.slice(3).split('.');
                    if (valid(n, p)) {
                        const node = model.find((x) => x.id === n);
                        node.bindings[p] = fromNode === INPUTS ? { kind: 'input', input: fromPort } : { kind: 'node', node: fromNode, port: fromPort };
                        view.selected = { kind: 'wire', to: n + '.' + p };
                    }
                }
                ctx.onChange(); // also when a picked-up wire was dropped nowhere: it is removed
            });
        }
    }

    function lastRunOf(ctx, nodeId) {
        const runs = ctx.lastRun && ctx.lastRun.blocks;
        return runs ? runs.find((b) => b.nodeId === nodeId) : null;
    }

    function curve(a, b) {
        const dx = Math.max(50, Math.abs(b.x - a.x) / 2);
        return 'M' + a.x + ',' + a.y + ' C' + (a.x + dx) + ',' + a.y + ' ' + (b.x - dx) + ',' + b.y + ' ' + b.x + ',' + b.y;
    }

    function path(d, cls) {
        const p = document.createElementNS(SVG, 'path');
        p.setAttribute('d', d);
        p.setAttribute('class', cls);
        return p;
    }

    function clamp(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function short(value) {
        const text = String(value == null ? '' : value);
        return text.length > 14 ? text.slice(0, 14) + '...' : text;
    }

    function cssEscape(text) {
        return window.CSS && CSS.escape ? CSS.escape(text) : text.replace(/"/g, '\\"');
    }

    // ------------------------------------------------------------------ inspector

    function renderInspector(box, container, model, ctx, byId) {
        clear(box);
        const sel = view.selected;
        if (sel && sel.kind === 'node' && model.some((n) => n.id === sel.id)) {
            box.appendChild(nodeInspector(model.find((n) => n.id === sel.id), container, model, ctx, byId));
        } else if (sel && sel.kind === 'wire') {
            box.appendChild(wireInspector(sel.to, model, ctx, byId));
        } else if (sel && sel.kind === 'block' && byId[sel.id]) {
            const v = byId[sel.id];
            box.appendChild(el('div.fg-insp-head', null, el('div.fg-insp-kicker', null, 'Block'), el('h3', null, v.definition.name),
                el('code', null, v.definition.id + ' ' + v.definition.version)));
            if (!ctx.readOnly && v.usable) {
                box.appendChild(el('button.btn.full', { onclick: () => addNode(v.definition.id, model, ctx, byId, null) }, 'Add to flow'));
            } else if (!v.usable) {
                box.appendChild(el('p.fg-warn', null, 'This block is not released or its data is not in the catalog yet: an admin releases it in Blocks.'));
            }
            box.appendChild(portsTable(v.definition, null));
            box.appendChild(docsPanel(v, 'node'));
        } else {
            box.appendChild(flowSummary(model, ctx, byId));
        }
    }

    function flowSummary(model, ctx, byId) {
        const order = executionOrder(model);
        const issues = problems(model, ctx, byId);
        return el('div', null,
            el('div.fg-insp-head', null, el('div.fg-insp-kicker', null, 'Flow'), el('h3', null, 'Overview')),
            el('p.muted', null, 'Click a node or a wire to see its details, or a block on the left to read about it.'),
            el('div.editor-label', null, 'Execution order'),
            order.length ? el('ol.fg-order-list', null, order.map((id) => {
                const n = model.find((x) => x.id === id);
                const v = byId[n.block];
                return el('li', null, el('a', { href: '#', onclick: (e) => { e.preventDefault(); view.selected = { kind: 'node', id }; rerender(e.target.closest('.fg').parentNode, model, ctx); } },
                    el('code', null, id)), ' ', el('span.muted', null, v ? v.definition.name : n.block));
            })) : el('p.muted', null, 'No node yet.'),
            el('div.editor-label', null, 'To fix'),
            issues.length ? el('ul.fg-issues', null, issues.map((i) => el('li', null, i.text)))
                : el('p.fg-ok', null, model.length ? 'Nothing: every required input is connected.' : 'Add a node to start.'),
            el('div.editor-label', null, 'Port colors'),
            el('div.fg-legend', null, Object.entries(TYPE_COLORS).filter(([t]) => t !== 'datetime').map(([t]) => el('span', null, typeDot(t), ' ', t))),
            el('p.hint', null, 'A wire only joins an output and an input of the same color (type). Nodes with no wire between them may run in any order; a wire makes the source run first.'));
    }

    function nodeInspector(node, container, model, ctx, byId) {
        const v = byId[node.block];
        const d = v && v.definition;
        const box = el('div');
        box.appendChild(el('div.fg-insp-head', null,
            el('div.fg-insp-kicker', null, 'Node ' + (executionOrder(model).indexOf(node.id) + 1)),
            el('h3', null, d ? d.name : node.block),
            el('code', null, d ? d.id + ' ' + d.version : 'unavailable')));
        const idInput = el('input.node-id', {
            value: node.id, disabled: ctx.readOnly,
            title: 'The node id is the namespace of its outputs in the template',
            onchange: (e) => {
                const next = e.target.value.trim();
                if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(next) || next === INPUTS || next === 'report' || model.some((n) => n !== node && n.id === next)) {
                    e.target.value = node.id;
                    e.target.classList.add('invalid');
                    return;
                }
                const old = node.id;
                node.id = next;
                model.forEach((other) => Object.values(other.bindings).forEach((b) => { if (b.kind === 'node' && b.node === old) b.node = next; }));
                view.selected = { kind: 'node', id: next };
                ctx.onChange();
            }
        });
        box.appendChild(el('div.field', null, el('label', null, 'Node id (letters, digits, _)'), idInput));
        box.appendChild(FlowEditor.optionalToggle(node, ctx));
        if (!ctx.readOnly) {
            box.appendChild(el('div.row', null,
                el('button.btn-secondary', { onclick: () => removeNode(node.id, model, ctx) }, 'Remove node')));
        }
        if (!d) {
            box.appendChild(el('p.fg-warn', null, 'Block ' + node.block + ' is not available to you: it must be released, with data in the catalog, before this flow runs.'));
            return box;
        }
        if (!v.usable) box.appendChild(el('p.fg-warn', null, 'Not usable yet: an admin must release it, and its data must be in the catalog.'));

        const run = lastRunOf(ctx, node.id);
        if (run) {
            box.appendChild(el('div.editor-label', null, 'Last run'));
            box.appendChild(el('div.fg-run-box.' + (run.status === 'COMPLETED' ? 'ok' : 'failed'), null,
                el('div', null, el('strong', null, run.status), ' in ', run.durationMs + ' ms'),
                run.error ? el('div.error-text', null, run.error) : el('pre.fg-json', null, previewJson(run.outputs))));
        }

        box.appendChild(el('div.editor-label', null, 'Inputs: where each value comes from'));
        const ports = el('div.fg-insp-ports');
        if (!d.inputs.length) ports.appendChild(el('div.muted', null, 'This block has no inputs.'));
        d.inputs.forEach((port) => {
            ports.appendChild(FlowEditor.portRow(node, port, model, ctx, blocksIdToDefinition(byId)));
            if (port.description) ports.appendChild(el('div.fg-port-desc', null, port.description));
        });
        box.appendChild(ports);

        box.appendChild(el('div.editor-label', null, 'Outputs: click to copy the template variable'));
        box.appendChild(el('div.fg-outputs', null, d.outputs.map((o) => {
            const variable = '{{' + node.id + '.' + o.name + '}}';
            return el('div.fg-output', null,
                el('button.variable', { title: 'Copy ' + variable, onclick: (e) => copy(variable, e.currentTarget) },
                    el('code', null, variable), el('span.type', null, o.type)),
                o.description ? el('div.fg-port-desc', null, o.description) : null);
        })));
        box.appendChild(docsPanel(v, node.id));
        return box;
    }

    function blocksIdToDefinition(byId) {
        const out = {};
        Object.entries(byId).forEach(([id, v]) => { out[id] = v.definition; });
        return out;
    }

    function wireInspector(to, model, ctx, byId) {
        const w = wires(model, byId).find((x) => x.to.node + '.' + x.to.port === to);
        const box = el('div', null, el('div.fg-insp-head', null, el('div.fg-insp-kicker', null, 'Connection'), el('h3', null, 'Wire')));
        if (!w) return el('div.muted', null, 'This connection no longer exists.');
        box.appendChild(el('div.fg-wire-desc', null,
            el('div', null, el('span.muted', null, 'from '), el('code', null, w.from.node + '.' + w.from.port)),
            el('div', null, el('span.muted', null, 'to '), el('code', null, w.to.node + '.' + w.to.port)),
            el('div', null, el('span.muted', null, 'type '), typeDot(w.type), ' ', w.type || '?')));
        box.appendChild(el('p.hint', null, 'The value produced by the source is passed, unchanged, to the target when the flow runs; the source node runs first.'));
        if (!ctx.readOnly) box.appendChild(el('button.btn-secondary', { onclick: () => removeWire(to, model, ctx) }, 'Remove connection'));
        return box;
    }

    function portsTable(d, nodeId) {
        const name = (p) => (nodeId ? nodeId + '.' : '') + p.name;
        return el('div.fg-ports-table', null,
            el('div.editor-label', null, 'Inputs'),
            d.inputs.length ? d.inputs.map((p) => el('div.fg-port-line', null, typeDot(p.type), el('code', null, name(p)), el('span.type', null, p.type),
                p.required ? el('span.req', null, ' required') : el('span.muted', null, ' optional'),
                p.description ? el('div.fg-port-desc', null, p.description) : null)) : el('div.muted', null, 'none'),
            el('div.editor-label', null, 'Outputs'),
            d.outputs.map((p) => el('div.fg-port-line', null, typeDot(p.type), el('code', null, name(p)), el('span.type', null, p.type),
                p.description ? el('div.fg-port-desc', null, p.description) : null)));
    }

    /** The Block documentation: what it answers, how it works, wiring tips, caveats, source, example. */
    function docsPanel(v, nodeId) {
        const docs = v.docs || {};
        const box = el('div.fg-docs');
        if (docs.summary) box.appendChild(el('p.fg-summary', null, docs.summary));
        if (docs.howItWorks && docs.howItWorks.length) {
            box.appendChild(el('div.editor-label', null, 'How it works'));
            box.appendChild(el('ol', null, docs.howItWorks.map((t) => el('li', null, t))));
        }
        if (docs.connect && docs.connect.length) {
            box.appendChild(el('div.editor-label', null, 'How to connect it'));
            box.appendChild(el('ul', null, docs.connect.map((t) => el('li', null, t))));
        }
        if (docs.limitations && docs.limitations.length) {
            box.appendChild(el('div.editor-label', null, 'Limitations'));
            box.appendChild(el('ul.fg-limits', null, docs.limitations.map((t) => el('li', null, t))));
        }
        if (docs.source) {
            box.appendChild(el('div.editor-label', null, 'Data source'));
            box.appendChild(el('p.muted', null, docs.source));
        }
        if (docs.example) {
            const example = docs.example.split('<node>').join(nodeId || 'node');
            box.appendChild(el('div.editor-label', null, 'Template example'));
            box.appendChild(el('pre.fg-example', { title: 'Click to copy', onclick: (e) => copy(example, e.currentTarget) }, example));
        }
        if (!box.childNodes.length) box.appendChild(el('p.muted', null, v.definition.description || 'No documentation.'));
        return box;
    }

    function copy(text, target) {
        const done = () => { target.classList.add('copied'); setTimeout(() => target.classList.remove('copied'), 900); };
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, () => {});
    }

    /** Outputs of a run, readable: long strings (images) cut, lists shortened. */
    function previewJson(value) {
        const text = JSON.stringify(value, (k, v) => {
            if (typeof v === 'string' && v.startsWith('data:image/')) return '[image ' + Math.round(v.length / 1024) + ' KB]';
            if (typeof v === 'string' && v.length > 80) return v.slice(0, 80) + '...';
            if (Array.isArray(v) && v.length > 3) return v.slice(0, 3).concat(['... ' + (v.length - 3) + ' more']);
            return v;
        }, 2) || '';
        return text.length > 2500 ? text.slice(0, 2500) + '\n...' : text;
    }

    // ------------------------------------------------------------------ help

    function helpPanel(container, model, ctx) {
        const close = () => { view.help = false; rerender(container, model, ctx); };
        return el('div.fg-help', { onclick: (e) => { if (e.target.classList.contains('fg-help')) close(); } },
            el('div.fg-help-card', null,
                el('div.fg-help-top', null, el('h3', null, 'How a flow works'), el('button.icon', { onclick: close }, 'Close')),
                el('div.fg-steps', null,
                    step('1', 'Inputs', 'What the reader types when running the report (Inputs tab), e.g. the CAR code. They appear as the "Report inputs" box, on the left.'),
                    step('2', 'Blocks', 'Each box is a Block: it reads one dataset (CAR, BR Geo, Satellite Hub...) or computes something, and produces typed outputs. Pick them from the palette; click one to read what it does and what it cannot tell.'),
                    step('3', 'Wires', 'Drag from an output dot (right side) to an input dot (left side). Colors are types: a wire only joins the same color. Example: car-property-geometry.geometry (green) feeds the geometry of every BR Geo block.'),
                    step('4', 'Order', 'The number on each box is the execution order. A wire makes its source run first; boxes with no wire between them keep the order you added them.'),
                    step('5', 'Template', 'Every output becomes a variable: {{node.output}}. Copy it from the inspector and use it in the Template tab; lists repeat with {{#node.list}}...{{/node.list}}.'),
                    step('6', 'Test', 'Run a Preview: each box then shows how long it took or why it failed, and the inspector shows what it returned.')),
                el('div.editor-label', null, 'Tips'),
                el('ul', null,
                    el('li', null, 'Inputs marked * are required; a box with an unconnected required input has a red border.'),
                    el('li', null, 'An input can also take a fixed value (set it in the inspector), e.g. a start date for DETER alerts.'),
                    el('li', null, 'Drag a connected input dot to move or remove its wire; select a box or wire and press Delete to remove it.'),
                    el('li', null, 'Big blocks show only their connected outputs and the first few: click "+N more outputs" to see and wire the others.'),
                    el('li', null, 'Mouse wheel zooms, dragging the background moves the canvas. Positions are saved with the draft.'),
                    el('li', null, 'Only released blocks with data in the catalog can run: an admin releases them in the Blocks screen.')),
                el('div.fg-legend', null, Object.entries(TYPE_COLORS).filter(([t]) => t !== 'datetime').map(([t]) => el('span', null, typeDot(t), ' ', t)))));
    }

    function step(number, title, text) {
        return el('div.fg-step', null, el('span.fg-step-num', null, number), el('div', null, el('strong', null, title), el('div.muted', null, text)));
    }

    window.ReportsFlowGraph = { render, docsPanel, TYPE_COLORS };
})();
