/*
 * The REPORTS panel: report list, the editor (Inputs, Flow, Template), Preview and PDF,
 * execution history with re-run, version diff, and the Blocks screen.
 *
 * State lives in one object; each section re-renders from it. Saving sends the whole draft; the
 * server answers with the version and its validation, which is shown as it comes.
 */
(function () {
    'use strict';

    const api = window.ReportsApi;
    const { el, clear, formatDate } = window.ReportsDom;
    const FlowEditor = window.ReportsFlowEditor;
    const TYPES = ['string', 'number', 'boolean', 'date', 'datetime', 'object', 'array', 'geometry'];
    const STRUCTURED = ['object', 'array', 'geometry'];
    const $ = (id) => document.getElementById(id);

    const state = {
        me: null,
        blocks: [],
        reports: [],
        report: null,
        versions: [],
        view: null,         // VersionView of the selected version
        inputs: [],         // working copy of the inputs
        flow: [],           // working model of the flow editor
        page: null,
        dirty: false,
        discardArmed: false,
        tab: 'inputs',
        lastExecution: null
    };

    let templateEditor = null;

    // ---------------------------------------------------------------- status and messages

    function setStatus(text, ready) {
        $('status-text').textContent = text;
        $('status-dot').classList.toggle('ready', !!ready);
    }

    function showMessage(text, kind, details) {
        const box = clear($('message'));
        box.className = 'message ' + (kind || 'info');
        box.appendChild(el('div', null, text));
        if (details && details.length) box.appendChild(el('ul', null, details.map((d) => el('li', null, d))));
        box.appendChild(el('button.icon', { onclick: () => { box.className = 'message hidden'; } }, 'Close'));
    }

    function fail(error) {
        showMessage(error.message || String(error), 'error', error.details);
    }

    async function guarded(button, action) {
        if (button) button.disabled = true;
        try {
            await action();
        } catch (e) {
            fail(e);
        } finally {
            if (button) button.disabled = false;
        }
    }

    function markDirty() {
        if (!isDraft()) return;
        state.dirty = true;
        state.discardArmed = false;
        $('dirty').classList.remove('hidden');
        $('validation').classList.add('stale');
    }

    function isDraft() {
        return state.view && state.view.version.status === 'DRAFT';
    }

    function canEdit() {
        return state.report && state.me && (state.me.admin || state.me.id === state.report.ownerId);
    }

    /** Leaving unsaved work takes two clicks; a dialog would block the host's iframe. */
    function confirmDiscard() {
        if (!state.dirty || state.discardArmed) return true;
        state.discardArmed = true;
        showMessage('There are unsaved changes. Click again to discard them, or save first.', 'warn');
        return false;
    }

    // ---------------------------------------------------------------- navigation

    function showSection(name) {
        ['reports', 'blocks'].forEach((s) => {
            $('nav-' + s).classList.toggle('active', s === name);
            $('side-' + s).classList.toggle('hidden', s !== name);
        });
        $('view-blocks').classList.toggle('hidden', name !== 'blocks');
        $('view-report').classList.toggle('hidden', name !== 'reports' || !state.report);
        $('view-empty').classList.toggle('hidden', name !== 'reports' || !!state.report);
        if (name === 'blocks') loadBlocks().then(renderBlocksView).catch(fail);
    }

    function showTab(name) {
        state.tab = name;
        document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
        document.querySelectorAll('.tab-panel').forEach((p) => p.classList.toggle('hidden', p.id !== 'tab-' + name));
        if (name === 'template' && templateEditor) {
            refreshVariables();
            setTimeout(() => templateEditor.refresh(), 0);
        }
        if (name === 'preview') renderPreviewInputs();
        if (name === 'executions') loadExecutions().catch(fail);
        if (name === 'versions') renderVersionsTab();
    }

    // ---------------------------------------------------------------- reports list

    async function loadReports() {
        state.reports = await api.get('/reports');
        renderReportList();
    }

    function renderReportList() {
        const list = clear($('report-list'));
        if (!state.reports.length) {
            list.appendChild(el('div.placeholder.small', null, 'No report yet.'));
            return;
        }
        state.reports.forEach((r) => {
            list.appendChild(el('div.list-item' + (state.report && state.report.id === r.id ? '.active' : ''), {
                onclick: () => { if (confirmDiscard()) selectReport(r.id).catch(fail); }
            },
                el('div.list-title', null, r.name),
                el('div.list-meta', null, r.publishedVersion ? 'published v' + r.publishedVersion : 'not published',
                    ' - ', r.ownerName || r.ownerId)));
        });
    }

    async function createReport() {
        const name = $('new-report-name').value.trim();
        if (!name) return showMessage('Give the report a name.', 'warn');
        const report = await api.post('/reports', { name, description: $('new-report-description').value.trim() || null });
        $('new-report-name').value = '';
        $('new-report-description').value = '';
        await loadReports();
        await selectReport(report.id);
    }

    async function selectReport(id, versionNumber) {
        state.report = await api.get('/reports/' + id);
        state.versions = await api.get('/reports/' + id + '/versions');
        const chosen = versionNumber || (state.versions[0] && state.versions[0].version);
        await selectVersion(chosen);
        renderReportList();
        showSection('reports');
    }

    async function selectVersion(number) {
        state.view = await api.get('/reports/' + state.report.id + '/versions/' + number);
        const v = state.view.version;
        state.inputs = (v.inputs || []).map((i) => Object.assign({}, i));
        state.flow = FlowEditor.fromFlow(v.flow);
        state.page = Object.assign({}, v.page);
        state.dirty = false;
        state.discardArmed = false;
        state.lastExecution = null;
        $('dirty').classList.add('hidden');
        templateEditor.setValue(v.templateHtml, v.templateCss);
        refreshVariables();
        renderReportHead();
        renderInputs();
        renderFlow();
        renderPage();
        renderValidation();
        showTab(state.tab);
    }

    function renderReportHead() {
        const r = state.report;
        $('report-name').textContent = r.name;
        $('report-meta').textContent = (r.description ? r.description + ' - ' : '') + 'owner ' + (r.ownerName || r.ownerId)
            + ' - updated ' + formatDate(r.updatedAt);
        const select = clear($('version-select'));
        state.versions.forEach((v) => select.appendChild(el('option', { value: v.version },
            'v' + v.version + ' - ' + (v.status === 'DRAFT' ? 'draft' : 'published'))));
        select.value = state.view.version.version;
        const draft = isDraft();
        const editable = canEdit();
        $('version-status').textContent = draft ? 'DRAFT' : 'PUBLISHED';
        $('version-status').className = 'chip ' + (draft ? 'warn' : 'ok');
        $('btn-save').classList.toggle('hidden', !draft || !editable);
        $('btn-publish').classList.toggle('hidden', !draft || !editable);
        const hasDraft = state.versions.some((v) => v.status === 'DRAFT');
        $('btn-new-draft').classList.toggle('hidden', hasDraft || !editable);
        $('btn-rename').classList.toggle('hidden', !editable);
        $('btn-delete').classList.toggle('hidden', !editable);
        $('readonly-note').classList.toggle('hidden', draft && editable);
        $('readonly-note').textContent = !editable ? 'Only the owner or an admin can change this report.'
            : 'Published versions are frozen. Create a new draft to change it.';
        templateEditor.setReadOnly(!draft || !editable);
    }

    function renderValidation() {
        const box = clear($('validation'));
        box.classList.remove('stale');
        const v = state.view.validation;
        const groups = [['Inputs', v.inputs], ['Flow', v.flow], ['Template', v.template], ['Page', v.page], ['Blocks', v.blocks]];
        const total = groups.reduce((n, g) => n + g[1].length, 0);
        if (!total) {
            box.appendChild(el('div.valid', null, el('span.dot.ready'), ' Ready to run'));
            return;
        }
        const details = el('details', null, el('summary', null, total + (total === 1 ? ' problem' : ' problems') + ' to solve before running or publishing'));
        groups.filter((g) => g[1].length).forEach(([name, problems]) => {
            details.appendChild(el('div.problem-group', null, el('strong', null, name), el('ul', null, problems.map((p) => el('li', null, p)))));
        });
        box.appendChild(details);
    }

    /**
     * The variables of what is being edited, saved or not: report inputs plus the outputs of each
     * node's Block. Same rule as the server's TemplateScope, which validates on save.
     */
    function localVariables() {
        const byId = {};
        state.blocks.forEach((b) => { byId[b.definition.id] = b.definition; });
        const list = state.inputs.filter((i) => i.name).map((i) => ({ path: 'inputs.' + i.name, type: i.type, source: 'report input' }));
        state.flow.forEach((node) => {
            const def = byId[node.block];
            (def ? def.outputs : []).forEach((o) => list.push({ path: node.id + '.' + o.name, type: o.type, source: node.block }));
        });
        return list;
    }

    function refreshVariables() {
        const list = localVariables();
        templateEditor.setVariables(list);
        const box = clear($('variable-list'));
        if (!list.length) box.appendChild(el('div.muted', null, 'Add inputs and nodes to get variables.'));
        list.forEach((v) => box.appendChild(el('button.variable', {
            title: 'From ' + v.source + '. Click to insert.',
            onclick: () => { if (isDraft() && canEdit()) templateEditor.insertVariable(v.path); }
        }, el('code', null, '{{' + v.path + '}}'), el('span.type', null, v.type))));
    }

    // ---------------------------------------------------------------- inputs tab

    function renderInputs() {
        const box = clear($('inputs-editor'));
        const disabled = !isDraft() || !canEdit();
        if (!state.inputs.length) box.appendChild(el('div.placeholder.small', null, 'No input. Inputs are what people type when they run the report, e.g. propertyId.'));
        state.inputs.forEach((input, index) => {
            box.appendChild(el('div.input-row', null,
                el('input', { value: input.name || '', placeholder: 'name', disabled, oninput: (e) => { input.name = e.target.value.trim(); markDirty(); } }),
                typeSelect(input, disabled),
                el('input', { value: input.label || '', placeholder: 'label shown in the form', disabled, oninput: (e) => { input.label = e.target.value; markDirty(); } }),
                el('label.check', null, el('input', { type: 'checkbox', checked: input.required, disabled, onchange: (e) => { input.required = e.target.checked; markDirty(); } }), ' required'),
                disabled ? null : el('button.icon.danger', { onclick: () => { state.inputs.splice(index, 1); markDirty(); renderInputs(); renderFlow(); } }, 'Remove')));
        });
        if (!disabled) {
            box.appendChild(el('button.btn-secondary', {
                onclick: () => { state.inputs.push({ name: '', type: 'string', required: true, label: '' }); markDirty(); renderInputs(); }
            }, 'Add input'));
        }
    }

    function typeSelect(input, disabled) {
        const select = el('select', { disabled, onchange: (e) => { input.type = e.target.value; markDirty(); renderFlow(); } },
            TYPES.map((t) => el('option', { value: t }, t)));
        select.value = input.type || 'string';
        return select;
    }

    // ---------------------------------------------------------------- flow tab

    function renderFlow() {
        FlowEditor.render($('flow-editor'), state.flow, {
            blocks: state.blocks,
            inputs: state.inputs.filter((i) => i.name),
            readOnly: !isDraft() || !canEdit(),
            onChange: () => { markDirty(); renderFlow(); },
            onDirty: markDirty
        });
    }

    // ---------------------------------------------------------------- page settings

    function renderPage() {
        const disabled = !isDraft() || !canEdit();
        ['size', 'orientation', 'marginMm'].forEach((field) => {
            const input = $('page-' + field);
            input.disabled = disabled;
            input.value = state.page[field] == null ? '' : state.page[field];
            input.onchange = () => {
                state.page[field] = field === 'marginMm' ? Number(input.value) : input.value;
                markDirty();
            };
        });
    }

    // ---------------------------------------------------------------- save / publish / drafts

    function draftBody() {
        const byId = {};
        state.blocks.forEach((b) => { byId[b.definition.id] = b.definition; });
        return {
            inputs: state.inputs,
            flow: FlowEditor.toFlow(state.flow, byId),
            templateHtml: templateEditor.getHtml(),
            templateCss: templateEditor.getCss(),
            page: state.page
        };
    }

    async function save() {
        const number = state.view.version.version;
        state.view = await api.put('/reports/' + state.report.id + '/versions/' + number, draftBody());
        state.dirty = false;
        $('dirty').classList.add('hidden');
        refreshVariables();
        renderValidation();
        showMessage('Draft v' + number + ' saved.', 'ok');
    }

    async function publish() {
        if (state.dirty) await save();
        const number = state.view.version.version;
        await api.post('/reports/' + state.report.id + '/versions/' + number + '/publish');
        await loadReports();
        await selectReport(state.report.id, number);
        showMessage('Version ' + number + ' published.', 'ok');
    }

    async function newDraft() {
        const draft = await api.post('/reports/' + state.report.id + '/versions');
        await selectReport(state.report.id, draft.version);
        showMessage('Draft v' + draft.version + ' created from the latest version.', 'ok');
    }

    async function rename() {
        const name = $('rename-input').value.trim();
        if (!name) return;
        state.report = await api.put('/reports/' + state.report.id, { name, description: state.report.description });
        $('rename-box').classList.add('hidden');
        renderReportHead();
        await loadReports();
    }

    async function deleteReport() {
        if (!$('btn-delete').classList.contains('armed')) {
            $('btn-delete').classList.add('armed');
            $('btn-delete').textContent = 'Click to confirm';
            setTimeout(() => { $('btn-delete').classList.remove('armed'); $('btn-delete').textContent = 'Delete'; }, 4000);
            return;
        }
        await api.del('/reports/' + state.report.id);
        state.report = null;
        state.dirty = false;
        await loadReports();
        showSection('reports');
        showMessage('Report deleted. Its executions remain in the history.', 'ok');
    }

    // ---------------------------------------------------------------- preview and execution

    function renderPreviewInputs() {
        const box = clear($('run-inputs'));
        const inputs = state.inputs.filter((i) => i.name);
        if (!inputs.length) box.appendChild(el('div.muted', null, 'This report has no inputs.'));
        inputs.forEach((input) => {
            const id = 'run-input-' + input.name;
            const field = input.type === 'boolean'
                ? el('select', { id }, el('option', { value: '' }, '-'), el('option', { value: 'true' }, 'true'), el('option', { value: 'false' }, 'false'))
                : STRUCTURED.includes(input.type)
                    ? el('textarea', { id, rows: 3, placeholder: 'JSON' })
                    : el('input', { id, type: input.type === 'date' ? 'date' : 'text', placeholder: input.type });
            box.appendChild(el('div.field', null, el('label', { for: id }, (input.label || input.name) + (input.required ? ' *' : '')), field));
        });
        $('run-version-note').textContent = state.dirty
            ? 'Unsaved changes are saved before the preview runs.'
            : 'Runs version ' + state.view.version.version + '.';
    }

    function runInputs() {
        const values = {};
        state.inputs.filter((i) => i.name).forEach((input) => {
            const node = $('run-input-' + input.name);
            if (!node || node.value === '') return;
            if (STRUCTURED.includes(input.type)) {
                try { values[input.name] = JSON.parse(node.value); } catch (e) { values[input.name] = node.value; }
            } else {
                values[input.name] = node.value;
            }
        });
        return values;
    }

    async function runPreview() {
        if (state.dirty) await save();
        setRunning(true);
        try {
            const result = await api.post('/reports/' + state.report.id + '/preview',
                { version: state.view.version.version, inputs: runInputs() });
            renderRun(result.execution, result.html);
        } finally {
            setRunning(false);
        }
    }

    async function runPreviewPdf() {
        if (state.dirty) await save();
        setRunning(true);
        try {
            await api.download('POST', '/reports/' + state.report.id + '/preview/pdf',
                { version: state.view.version.version, inputs: runInputs() }, slug(state.report.name) + '-preview.pdf');
        } finally {
            setRunning(false);
        }
    }

    async function runExecute() {
        if (state.dirty) await save();
        setRunning(true);
        try {
            const execution = await api.post('/reports/' + state.report.id + '/execute',
                { version: state.view.version.version, inputs: runInputs() });
            let html = null;
            if (execution.status === 'COMPLETED') html = await api.get('/executions/' + execution.id + '/result');
            renderRun(execution, html);
            showMessage('Execution #' + execution.id + ' stored (' + execution.status + ').', execution.status === 'COMPLETED' ? 'ok' : 'error');
        } finally {
            setRunning(false);
        }
    }

    function setRunning(running) {
        $('run-spinner').classList.toggle('hidden', !running);
        ['btn-preview', 'btn-preview-pdf', 'btn-execute'].forEach((id) => { $(id).disabled = running; });
    }

    /** Status, error, one row per Block, and the report in a sandboxed frame (no scripts, no origin). */
    function renderRun(execution, html) {
        state.lastExecution = execution;
        const box = clear($('run-result'));
        box.appendChild(executionSummary(execution));
        box.appendChild(blockRunsTable(execution.blocks || []));
        const frame = $('preview-frame');
        frame.srcdoc = html || '';
        frame.classList.toggle('hidden', !html);
        $('preview-empty').classList.toggle('hidden', !!html);
    }

    function executionSummary(execution) {
        const ok = execution.status === 'COMPLETED';
        const summary = el('div.run-summary', null,
            el('span.chip.' + (ok ? 'ok' : 'error'), null, execution.status),
            el('span.muted', null, (execution.id ? '#' + execution.id + ' - ' : 'preview - ') + 'v' + execution.reportVersion
                + ' - ' + (execution.durationMs == null ? '-' : execution.durationMs + ' ms')));
        if (execution.error) {
            summary.appendChild(el('div.run-error', null,
                el('strong', null, execution.error.message),
                execution.error.stage ? el('span.muted', null, ' (' + execution.error.stage + ')') : null,
                el('ul', null, (execution.error.details || []).map((d) => el('li', null, d)))));
        }
        return summary;
    }

    function blockRunsTable(blocks) {
        if (!blocks.length) return el('div');
        return el('table.table', null,
            el('thead', null, el('tr', null, ['Node', 'Block', 'Status', 'Time', 'Outputs'].map((h) => el('th', null, h)))),
            el('tbody', null, blocks.map((b) => el('tr', null,
                el('td', null, b.nodeId),
                el('td', null, b.blockId + ' ' + b.blockVersion),
                el('td', null, el('span.chip.' + (b.status === 'COMPLETED' ? 'ok' : 'error'), null, b.status)),
                el('td', null, b.durationMs + ' ms'),
                el('td', null, b.error ? el('span.error-text', null, b.error) : el('code.json', null, shortJson(b.outputs)))))));
    }

    function shortJson(value) {
        const text = JSON.stringify(value);
        return text && text.length > 160 ? text.slice(0, 160) + '...' : (text || '');
    }

    function slug(text) {
        return (text || 'report').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'report';
    }

    // ---------------------------------------------------------------- executions tab

    async function loadExecutions() {
        const list = await api.get('/reports/' + state.report.id + '/executions');
        const box = clear($('executions'));
        if (!list.length) {
            box.appendChild(el('div.placeholder.small', null, 'No stored execution yet. Use Execute in the Preview tab.'));
            return;
        }
        box.appendChild(el('table.table', null,
            el('thead', null, el('tr', null, ['#', 'Version', 'Status', 'By', 'When', 'Time', ''].map((h) => el('th', null, h)))),
            el('tbody', null, list.map((e) => el('tr', null,
                el('td', null, String(e.id), e.rerunOf ? el('span.muted', null, ' (re-run of #' + e.rerunOf + ')') : null),
                el('td', null, 'v' + e.reportVersion),
                el('td', null, el('span.chip.' + (e.status === 'COMPLETED' ? 'ok' : 'error'), null, e.status)),
                el('td', null, e.startedBy || '-'),
                el('td', null, formatDate(e.createdAt)),
                el('td', null, e.durationMs == null ? '-' : e.durationMs + ' ms'),
                el('td.actions', null,
                    el('button.icon', { onclick: () => showExecution(e.id).catch(fail) }, 'Details'),
                    e.status === 'COMPLETED' ? el('button.icon', { onclick: () => api.download('GET', '/executions/' + e.id + '/result?format=pdf', undefined, slug(state.report.name) + '-' + e.id + '.pdf').catch(fail) }, 'PDF') : null,
                    el('button.icon', { onclick: (ev) => guarded(ev.target, () => rerun(e.id)) }, 'Re-run')))))));
    }

    async function showExecution(id) {
        const execution = await api.get('/executions/' + id);
        let html = null;
        if (execution.status === 'COMPLETED') html = await api.get('/executions/' + id + '/result');
        showTab('preview');
        renderRun(execution, html);
    }

    async function rerun(id) {
        const result = await api.post('/executions/' + id + '/rerun');
        const notes = result.warnings.slice();
        notes.unshift(result.identicalValues ? 'Every block produced the same outputs as in #' + id + '.'
            : 'The outputs differ from #' + id + ' (or one of the runs failed).');
        showMessage('Re-run #' + result.execution.id + ' of #' + id + ': ' + result.execution.status,
            result.execution.status === 'COMPLETED' ? 'ok' : 'error', notes);
        await loadExecutions();
    }

    // ---------------------------------------------------------------- versions tab

    function renderVersionsTab() {
        const from = clear($('diff-from'));
        const to = clear($('diff-to'));
        state.versions.slice().reverse().forEach((v) => {
            from.appendChild(el('option', { value: v.version }, 'v' + v.version));
            to.appendChild(el('option', { value: v.version }, 'v' + v.version));
        });
        const current = state.view.version.version;
        to.value = current;
        from.value = Math.max(1, current - 1);
        const list = clear($('version-list'));
        state.versions.forEach((v) => list.appendChild(el('div.list-item.compact', null,
            el('span.chip.' + (v.status === 'DRAFT' ? 'warn' : 'ok'), null, 'v' + v.version + ' ' + v.status.toLowerCase()),
            el('span.muted', null, ' created ' + formatDate(v.createdAt) + (v.publishedAt ? ', published ' + formatDate(v.publishedAt) : '')))));
        clear($('diff-output'));
    }

    async function showDiff() {
        const diff = await api.get('/reports/' + state.report.id + '/versions/' + $('diff-from').value + '/diff/' + $('diff-to').value);
        const box = clear($('diff-output'));
        diff.parts.forEach((part) => {
            const lines = el('pre.diff');
            part.lines.forEach((line) => lines.appendChild(el('div.diff-' + line.op.toLowerCase(), null,
                (line.op === 'ADDED' ? '+ ' : line.op === 'REMOVED' ? '- ' : '  ') + line.text)));
            box.appendChild(el('details', { open: part.changed },
                el('summary', null, part.name, ' ', el('span.chip.' + (part.changed ? 'warn' : 'ok'), null, part.changed ? 'changed' : 'same')),
                lines));
        });
    }

    // ---------------------------------------------------------------- blocks

    async function loadBlocks() {
        state.blocks = await api.get('/blocks');
        return state.blocks;
    }

    function renderBlocksView() {
        const box = clear($('blocks'));
        const admin = state.me && state.me.admin;
        $('blocks-intro').textContent = admin
            ? 'Every block, with what the data catalog says about its data. A block can be released when its data is AVAILABLE; once released, everyone can use it.'
            : 'Blocks you can use in your flows: their data is in the catalog and an admin released them.';
        $('catalog-actions').classList.toggle('hidden', !admin);
        if (!state.blocks.length) {
            box.appendChild(el('div.placeholder', null, admin ? 'The app has no block yet.' : 'No block is available yet.'));
            return;
        }
        state.blocks.forEach((b) => box.appendChild(blockCard(b, admin)));
        const origin = state.blocks[0].availability.catalogOrigin;
        $('catalog-origin').textContent = 'Catalog: ' + origin + (state.blocks[0].availability.catalogError ? ' - ' + state.blocks[0].availability.catalogError : '');
    }

    function blockCard(view, admin) {
        const d = view.definition;
        const a = view.availability;
        const statusClass = a.status === 'AVAILABLE' ? 'ok' : a.status === 'STALE' ? 'warn' : 'error';
        const card = el('div.card.block-card', null,
            el('div.block-head', null,
                el('strong', null, d.name), el('code', null, d.id + ' ' + d.version),
                el('span.chip.' + statusClass, null, a.status),
                view.release ? el('span.chip.ok', null, 'released') : el('span.chip', null, 'not released')),
            el('div.muted', null, d.description || ''),
            el('div.ports-summary', null,
                el('div', null, el('span.muted', null, 'Inputs: '), d.inputs.map((p) => el('code', null, p.name + ':' + p.type + (p.required ? '*' : '')))),
                el('div', null, el('span.muted', null, 'Outputs: '), d.outputs.map((p) => el('code', null, p.name + ':' + p.type)))));
        if (a.checks.length) {
            card.appendChild(el('table.table.compact', null,
                el('thead', null, el('tr', null, ['Data', 'Columns', 'Status', 'Last update', 'Source'].map((h) => el('th', null, h)))),
                el('tbody', null, a.checks.map((c) => el('tr', null,
                    el('td', null, el('code', null, c.requirement.schema + '.' + c.requirement.table)),
                    el('td', null, c.requirement.columns.join(', ')),
                    el('td', null, el('span.chip.' + (c.status === 'AVAILABLE' ? 'ok' : c.status === 'STALE' ? 'warn' : 'error'), null, c.status),
                        c.problems.length ? el('div.error-text', null, c.problems.join('; ')) : null),
                    el('td', null, c.lastUpdate || '-'),
                    el('td', null, c.sourceId || '-'))))));
        } else {
            card.appendChild(el('div.muted', null, 'Needs no catalog data.'));
        }
        if (view.release) {
            card.appendChild(el('div.muted', null, 'Released by ' + (view.release.releasedByName || view.release.releasedBy) + ' on '
                + formatDate(view.release.releasedAt) + ' (version ' + view.release.blockVersion + ')'));
        }
        if (admin) {
            card.appendChild(el('div.row', null, view.release
                ? el('button.btn-secondary', { onclick: (e) => guarded(e.target, async () => { await api.del('/blocks/' + d.id + '/release'); await loadBlocks(); renderBlocksView(); }) }, 'Revoke')
                : el('button.btn', { disabled: a.status !== 'AVAILABLE', onclick: (e) => guarded(e.target, async () => { await api.post('/blocks/' + d.id + '/release'); await loadBlocks(); renderBlocksView(); }) }, 'Release')));
        }
        return card;
    }

    async function refreshCatalog() {
        await api.post('/catalog/refresh');
        await loadBlocks();
        renderBlocksView();
        showMessage('Catalog read again.', 'ok');
    }

    // ---------------------------------------------------------------- boot

    function bind() {
        $('nav-reports').onclick = () => showSection('reports');
        $('nav-blocks').onclick = () => { if (confirmDiscard()) showSection('blocks'); };
        $('btn-create').onclick = (e) => guarded(e.target, createReport);
        document.querySelectorAll('.tab').forEach((t) => { t.onclick = () => showTab(t.dataset.tab); });
        $('version-select').onchange = (e) => {
            if (!confirmDiscard()) { e.target.value = state.view.version.version; return; }
            selectVersion(Number(e.target.value)).catch(fail);
        };
        $('btn-save').onclick = (e) => guarded(e.target, save);
        $('btn-publish').onclick = (e) => guarded(e.target, publish);
        $('btn-new-draft').onclick = (e) => guarded(e.target, newDraft);
        $('btn-rename').onclick = () => { $('rename-input').value = state.report.name; $('rename-box').classList.toggle('hidden'); };
        $('btn-rename-ok').onclick = (e) => guarded(e.target, rename);
        $('btn-delete').onclick = (e) => guarded(e.target, deleteReport);
        $('btn-preview').onclick = () => runPreview().catch(fail);
        $('btn-preview-pdf').onclick = () => runPreviewPdf().catch(fail);
        $('btn-execute').onclick = () => runExecute().catch(fail);
        $('btn-diff').onclick = (e) => guarded(e.target, showDiff);
        $('btn-refresh-catalog').onclick = (e) => guarded(e.target, refreshCatalog);
        templateEditor = window.ReportsTemplateEditor.create($('html-editor'), $('css-editor'), { onChange: markDirty, readOnly: true });
    }

    async function boot() {
        bind();
        try {
            state.me = await api.get('/me');
            $('user-badge').textContent = (state.me.username || state.me.id) + ' - ' + state.me.role;
            if (!state.me.databaseReady) {
                setStatus('database not ready', false);
                showMessage('The database is not ready yet: ' + (state.me.databaseError || 'unknown error'), 'error');
                return;
            }
            await loadBlocks();
            await loadReports();
            setStatus('ready', true);
            showSection('reports');
        } catch (e) {
            setStatus('error', false);
            fail(e);
        }
    }

    document.addEventListener('DOMContentLoaded', boot);
})();
