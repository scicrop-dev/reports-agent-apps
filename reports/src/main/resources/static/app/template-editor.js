/*
 * The Template tab: CodeMirror editors for the template HTML and CSS, with completion of the
 * {{variables}} the Flow makes available. Typing "{{" opens the list; picking a name closes the tag.
 */
(function () {
    'use strict';

    const VARIABLE_PREFIX = /\{\{([#^/]?)([\w.]*)$/;

    function create(htmlHost, cssHost, options) {
        let variables = [];

        const common = {
            theme: 'default',
            lineNumbers: true,
            lineWrapping: true,
            matchBrackets: true,
            tabSize: 2,
            indentUnit: 2,
            readOnly: options.readOnly ? 'nocursor' : false
        };
        const html = window.CodeMirror(htmlHost, Object.assign({}, common, { mode: 'htmlmixed', autoCloseTags: true }));
        const css = window.CodeMirror(cssHost, Object.assign({}, common, { mode: 'css' }));

        function variableHint(cm) {
            const cursor = cm.getCursor();
            const before = cm.getLine(cursor.line).slice(0, cursor.ch);
            const match = before.match(VARIABLE_PREFIX);
            if (!match) return null;
            const typed = match[2];
            const list = variables
                .filter((v) => v.path.startsWith(typed))
                .map((v) => ({
                    text: v.path + (match[1] ? '' : '}}'),
                    displayText: v.path + '  ' + v.type
                }));
            return { list, from: window.CodeMirror.Pos(cursor.line, cursor.ch - typed.length), to: cursor };
        }

        html.on('inputRead', (cm, change) => {
            if (change.origin !== '+input') return;
            const cursor = cm.getCursor();
            const before = cm.getLine(cursor.line).slice(0, cursor.ch);
            if (VARIABLE_PREFIX.test(before)) cm.showHint({ hint: variableHint, completeSingle: false });
        });
        html.on('change', (cm, change) => { if (change.origin !== 'setValue') options.onChange(); });
        css.on('change', (cm, change) => { if (change.origin !== 'setValue') options.onChange(); });

        return {
            setValue(htmlText, cssText) {
                html.setValue(htmlText || '');
                css.setValue(cssText || '');
                html.clearHistory();
                css.clearHistory();
            },
            getHtml: () => html.getValue(),
            getCss: () => css.getValue(),
            setVariables(list) { variables = list || []; },
            setReadOnly(readOnly) {
                html.setOption('readOnly', readOnly ? 'nocursor' : false);
                css.setOption('readOnly', readOnly ? 'nocursor' : false);
            },
            insertVariable(path) {
                html.replaceSelection('{{' + path + '}}');
                html.focus();
            },
            refresh() {
                html.refresh();
                css.refresh();
            }
        };
    }

    window.ReportsTemplateEditor = { create };
})();
