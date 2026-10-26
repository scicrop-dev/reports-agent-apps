/*
 * Calls to the app's own API and a small DOM helper. Every call goes to BASE (the plugin base
 * path the host gave the page), never to an absolute URL.
 *
 * DOM nodes are built with el() and textContent, never with innerHTML from data: report names,
 * Block problems and execution errors come from users and the catalog.
 */
(function () {
    'use strict';

    const BASE = window.REPORTS_BASE || '';

    class ApiError extends Error {
        constructor(status, message, details) {
            super(message);
            this.status = status;
            this.details = details || [];
        }
    }

    async function call(method, path, body) {
        const options = { method, headers: {} };
        if (body !== undefined) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }
        const response = await fetch(BASE + '/api' + path, options);
        if (response.status === 204) return null;
        const type = response.headers.get('Content-Type') || '';
        const data = type.includes('application/json') ? await response.json() : await response.text();
        if (!response.ok) {
            const message = data && data.error ? data.error : ('HTTP ' + response.status);
            throw new ApiError(response.status, message, data && data.details);
        }
        return data;
    }

    /** Fetches a binary answer (PDF) and hands it to the browser as a download. */
    async function download(method, path, body, filename) {
        const options = { method, headers: {} };
        if (body !== undefined) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }
        const response = await fetch(BASE + '/api' + path, options);
        if (!response.ok) {
            let data = null;
            try { data = await response.json(); } catch (e) { /* not JSON */ }
            throw new ApiError(response.status, data && data.error ? data.error : ('HTTP ' + response.status), data && data.details);
        }
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
    }

    /**
     * el('div.card', {onclick: fn, title: 'x'}, child, 'text', ...)
     * The tag may carry classes after dots. Attributes starting with "on" become listeners.
     */
    function el(spec, attrs, ...children) {
        const [tag, ...classes] = spec.split('.');
        const node = document.createElement(tag || 'div');
        if (classes.length) node.className = classes.join(' ');
        if (attrs) {
            for (const [key, value] of Object.entries(attrs)) {
                if (value === undefined || value === null || value === false) continue;
                if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
                else if (key === 'value') node.value = value;
                else if (key === 'checked') node.checked = !!value;
                else if (key === 'disabled') node.disabled = !!value;
                else node.setAttribute(key, value === true ? '' : value);
            }
        }
        for (const child of children.flat()) {
            if (child === null || child === undefined || child === false) continue;
            node.appendChild(typeof child === 'string' || typeof child === 'number' ? document.createTextNode(String(child)) : child);
        }
        return node;
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
        return node;
    }

    function formatDate(value) {
        if (!value) return '-';
        const d = new Date(value);
        return isNaN(d) ? String(value) : d.toLocaleString();
    }

    window.ReportsApi = {
        get: (p) => call('GET', p),
        post: (p, b) => call('POST', p, b),
        put: (p, b) => call('PUT', p, b),
        del: (p) => call('DELETE', p),
        download,
        ApiError
    };
    window.ReportsDom = { el, clear, formatDate };
})();
