package com.infinitestack.reports.engine.flow;

/** "node.port", parsed. */
record PortRef(String node, String port) {

    static PortRef parse(String text) {
        if (text == null) return null;
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1 || text.indexOf('.', dot + 1) >= 0) return null;
        return new PortRef(text.substring(0, dot), text.substring(dot + 1));
    }

    @Override
    public String toString() {
        return node + "." + port;
    }
}
