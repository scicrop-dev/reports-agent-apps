/*
 * The Flow tab: the Flow as an ordered list of nodes, each with a Block and, for every input port,
 * where its value comes from (ROADMAP Phase 6: no visual graph before the MVP).
 *
 * It edits a working model and converts it to and from the stored JSON
 * ({nodes: [{id, block, values}], connections: [{from, to}]}). The order of the list is only for
 * reading: the engine runs nodes in dependency order.
 */
(function () {
    'use strict';

    const { el, clear } = window.ReportsDom;
    const STRUCTURED = ['object', 'array', 'geometry'];

    /** Stored Flow JSON -> editing model. */
    function fromFlow(flow) {
        const nodes = (flow && flow.nodes) || [];
        const connections = (flow && flow.connections) || [];
        return nodes.map((node) => {
            const bindings = {};
            Object.entries(node.values || {}).forEach(([port, value]) => {
                bindings[port] = { kind: 'fixed', value: typeof value === 'object' ? JSON.stringify(value) : String(value) };
            });
            connections.filter((c) => c.to && c.to.startsWith(node.id + '.')).forEach((c) => {
                const port = c.to.slice(node.id.length + 1);
                const [sourceNode, sourcePort] = c.from.split('.');
                bindings[port] = sourceNode === 'inputs'
                    ? { kind: 'input', input: sourcePort }
                    : { kind: 'node', node: sourceNode, port: sourcePort };
            });
            return { id: node.id, block: node.block, bindings };
        });
    }

    /** Editing model -> stored Flow JSON. Types of fixed values follow the port, as the engine expects. */
    function toFlow(model, blocksById) {
        const nodes = [];
        const connections = [];
        model.forEach((node) => {
            const definition = blocksById[node.block];
            const values = {};
            Object.entries(node.bindings).forEach(([port, binding]) => {
                if (binding.kind === 'fixed') {
                    const type = portType(definition, 'inputs', port);
                    values[port] = parseFixed(binding.value, type);
                } else if (binding.kind === 'input' && binding.input) {
                    connections.push({ from: 'inputs.' + binding.input, to: node.id + '.' + port });
                } else if (binding.kind === 'node' && binding.node && binding.port) {
                    connections.push({ from: binding.node + '.' + binding.port, to: node.id + '.' + port });
                }
            });
            nodes.push({ id: node.id, block: node.block, values });
        });
        return { nodes, connections };
    }

    function parseFixed(text, type) {
        if (STRUCTURED.includes(type)) {
            try { return JSON.parse(text); } catch (e) { return text; }
        }
        return text;
    }

    function portType(definition, kind, name) {
        if (!definition) return null;
        const port = (definition[kind] || []).find((p) => p.name === name);
        return port ? port.type : null;
    }

    function nextNodeId(model, block) {
        const base = (block || 'node').replace(/[^a-zA-Z0-9]+(.)?/g, (m, c) => (c ? c.toUpperCase() : '')).replace(/^[^a-zA-Z]+/, '') || 'node';
        let id = base;
        let i = 2;
        while (model.some((n) => n.id === id)) id = base + i++;
        return id;
    }

    /**
     * ctx: {blocks: BlockView[], inputs: InputDefinition[], readOnly, onChange(), onDirty()}
     * Admins receive every Block; only usable ones are offered for new nodes.
     */
    function render(container, model, ctx) {
        clear(container);
        const blocksById = {};
        ctx.blocks.forEach((b) => { blocksById[b.definition.id] = b.definition; });

        const usable = ctx.blocks.filter((b) => b.usable);
        if (!model.length) {
            container.appendChild(el('div.placeholder', null,
                usable.length ? 'No node yet. Add one to start the flow.'
                    : 'No block is available to you yet. Blocks appear once their data is in the catalog and an admin releases them.'));
        }

        model.forEach((node, index) => container.appendChild(nodeCard(node, index, model, ctx, blocksById)));

        if (!ctx.readOnly) {
            const select = el('select', { disabled: !usable.length },
                el('option', { value: '' }, 'Choose a block...'),
                usable.map((b) => el('option', { value: b.definition.id }, b.definition.name + ' (' + b.definition.id + ')')));
            const add = el('button.btn-secondary', {
                disabled: !usable.length,
                onclick: () => {
                    if (!select.value) return;
                    model.push({ id: nextNodeId(model, select.value), block: select.value, bindings: {} });
                    ctx.onChange();
                }
            }, 'Add node');
            container.appendChild(el('div.row.add-node', null, select, add));
        }
    }

    function nodeCard(node, index, model, ctx, blocksById) {
        const definition = blocksById[node.block];
        const view = ctx.blocks.find((b) => b.definition.id === node.block);
        const disabled = ctx.readOnly;
        const card = el('div.card.node');

        const idInput = el('input.node-id', {
            value: node.id, disabled, title: 'Node id: the namespace of its outputs in the template',
            onchange: (e) => {
                const old = node.id;
                node.id = e.target.value.trim();
                // Keep the connections that pointed to the renamed node.
                model.forEach((other) => Object.values(other.bindings).forEach((b) => {
                    if (b.kind === 'node' && b.node === old) b.node = node.id;
                }));
                ctx.onChange();
            }
        });
        const head = el('div.node-head', null,
            el('span.node-index', null, String(index + 1)),
            idInput,
            el('span.chip', { title: definition ? definition.description : 'This block is not available to you' },
                definition ? definition.name + ' ' + definition.version : node.block + ' (unavailable)'),
            view && !view.usable ? el('span.chip.warn', { title: 'Released and with data in the catalog are both required to run' }, 'not usable') : null,
            disabled ? null : el('span.node-actions', null,
                el('button.icon', { title: 'Move up', disabled: index === 0, onclick: () => { move(model, index, -1); ctx.onChange(); } }, 'Up'),
                el('button.icon', { title: 'Move down', disabled: index === model.length - 1, onclick: () => { move(model, index, 1); ctx.onChange(); } }, 'Down'),
                el('button.icon.danger', { title: 'Remove node', onclick: () => { model.splice(index, 1); ctx.onChange(); } }, 'Remove')));
        card.appendChild(head);

        if (!definition) {
            card.appendChild(el('div.muted', null, 'Block ' + node.block + ' is not released or has no data; this flow cannot run until it is.'));
            return card;
        }

        const ports = el('div.ports');
        definition.inputs.forEach((port) => ports.appendChild(portRow(node, port, model, ctx, blocksById)));
        if (!definition.inputs.length) ports.appendChild(el('div.muted', null, 'This block has no inputs.'));
        card.appendChild(ports);

        card.appendChild(el('div.outputs', null, el('span.muted', null, 'Outputs: '),
            definition.outputs.map((o) => el('code.output', { title: o.description || '' }, node.id + '.' + o.name + ' : ' + o.type))));
        return card;
    }

    function portRow(node, port, model, ctx, blocksById) {
        const binding = node.bindings[port.name] || { kind: 'none' };
        const disabled = ctx.readOnly;
        const options = [el('option', { value: 'none' }, port.required ? 'Not bound (required)' : 'Not bound')];
        ctx.inputs.filter((i) => i.type === port.type).forEach((i) => {
            options.push(el('option', { value: 'input:' + i.name }, 'Report input: ' + i.name));
        });
        model.filter((other) => other !== node).forEach((other) => {
            const def = blocksById[other.block];
            (def ? def.outputs : []).filter((o) => o.type === port.type).forEach((o) => {
                options.push(el('option', { value: 'node:' + other.id + '.' + o.name }, other.id + '.' + o.name));
            });
        });
        options.push(el('option', { value: 'fixed' }, 'Fixed value'));

        const current = binding.kind === 'input' ? 'input:' + binding.input
            : binding.kind === 'node' ? 'node:' + binding.node + '.' + binding.port
            : binding.kind;
        const select = el('select', {
            disabled,
            onchange: (e) => {
                const v = e.target.value;
                if (v === 'none') delete node.bindings[port.name];
                else if (v === 'fixed') node.bindings[port.name] = { kind: 'fixed', value: '' };
                else if (v.startsWith('input:')) node.bindings[port.name] = { kind: 'input', input: v.slice(6) };
                else {
                    const [n, p] = v.slice(5).split('.');
                    node.bindings[port.name] = { kind: 'node', node: n, port: p };
                }
                ctx.onChange();
            }
        }, options);
        select.value = current;
        if (select.value !== current && binding.kind !== 'none') {
            // The bound source no longer exists or changed type: show it instead of silently dropping it.
            select.insertBefore(el('option', { value: current }, 'Missing: ' + current.replace(/^(input|node):/, '')), select.firstChild);
            select.value = current;
        }

        const row = el('div.port', null,
            el('label', { title: port.description || '' }, port.name, el('span.type', null, port.type), port.required ? el('span.req', null, '*') : null),
            select);
        if (binding.kind === 'fixed') {
            const input = STRUCTURED.includes(port.type)
                ? el('textarea', { rows: 2, disabled, placeholder: 'JSON', value: binding.value, oninput: (e) => { binding.value = e.target.value; ctx.onDirty(); } })
                : el('input', { disabled, value: binding.value, placeholder: port.type, oninput: (e) => { binding.value = e.target.value; ctx.onDirty(); } });
            row.appendChild(input);
        }
        return row;
    }

    function move(list, index, delta) {
        const [item] = list.splice(index, 1);
        list.splice(index + delta, 0, item);
    }

    window.ReportsFlowEditor = { fromFlow, toFlow, render };
})();
