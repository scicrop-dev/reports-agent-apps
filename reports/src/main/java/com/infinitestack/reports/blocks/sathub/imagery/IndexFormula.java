package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

/**
 * A spectral index formula as Satellite Hub stores it ({@code gold.satellite_hub_analytics_indices.formula}),
 * e.g. {@code 2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)}: numbers, band names (STAC asset
 * names), + - * /, unary minus, parentheses. Parsed once, evaluated per pixel. Division by zero and any
 * NaN operand give NaN.
 *
 * <p>Besides that core, it accepts what the Satellite Hub validator lets through, with Python
 * semantics: {@code %} (floored modulo), {@code **} (power, right-associative, binds tighter than
 * unary minus) and the functions sqrt, abs, log, log10, exp, min, max, power, clip(x, lo, hi) and
 * where(cond, a, b) (a when cond is non-zero).
 *
 * <p>Grammar (recursive descent):
 * <pre>
 * expr    := term (('+' | '-') term)*
 * term    := unary (('*' | '/' | '%') unary)*
 * unary   := ('-' | '+') unary | power
 * power   := primary ('**' unary)?
 * primary := number | band | function '(' expr (',' expr)* ')' | '(' expr ')'
 * </pre>
 * Band names are resolved to indexes at parse time: {@link #evaluate(double[])} with values in
 * {@link #bandOrder()} is the allocation-free path for per-pixel loops.
 */
public final class IndexFormula {

    /** A compiled expression node over band values in {@link #bandOrder()}. */
    @FunctionalInterface
    private interface Node {
        double eval(double[] bands);
    }

    /** Function name to arity; checked at parse time. */
    private static final Map<String, Integer> FUNCTIONS = Map.of(
            "sqrt", 1, "abs", 1, "log", 1, "log10", 1, "exp", 1,
            "min", 2, "max", 2, "power", 2, "clip", 3, "where", 3);

    private final String source;
    private final Node root;
    private final List<String> bandOrder;
    private final Set<String> bands;

    private IndexFormula(String source, Node root, List<String> bandOrder) {
        this.source = source;
        this.root = root;
        this.bandOrder = List.copyOf(bandOrder);
        this.bands = Collections.unmodifiableSet(new LinkedHashSet<>(bandOrder));
    }

    /** @throws IllegalArgumentException with a readable reason for a syntax error or an unknown band */
    public static IndexFormula parse(String formula, Set<String> knownBands) {
        if (formula == null || formula.isBlank()) throw new IllegalArgumentException("Empty formula");
        if (knownBands == null) throw new IllegalArgumentException("knownBands is required");
        Parser parser = new Parser(formula, knownBands);
        Node root = parser.parseFormula();
        if (parser.bandOrder.isEmpty()) {
            throw new IllegalArgumentException("Formula '" + formula + "' reads no band");
        }
        return new IndexFormula(formula, root, parser.bandOrder);
    }

    /** Band names the formula reads, in order of first appearance. */
    public Set<String> bands() {
        return bands;
    }

    /** The band order {@link #evaluate(double[])} expects (same names as {@link #bands()}). */
    public List<String> bandOrder() {
        return bandOrder;
    }

    /** Value for one pixel; {@code band} returns the reflectance of a band at that pixel. */
    public double evaluate(ToDoubleFunction<String> band) {
        double[] values = new double[bandOrder.size()];
        for (int i = 0; i < values.length; i++) values[i] = band.applyAsDouble(bandOrder.get(i));
        return root.eval(values);
    }

    /**
     * Value for one pixel with the band values given in {@link #bandOrder()} order. No allocation and
     * no lookup: the path for per-pixel loops, reusing one array.
     */
    public double evaluate(double[] bandValues) {
        if (bandValues.length < bandOrder.size()) {
            throw new IllegalArgumentException("Expected " + bandOrder.size() + " band values " + bandOrder
                    + ", got " + bandValues.length);
        }
        return root.eval(bandValues);
    }

    @Override
    public String toString() {
        return source;
    }

    // ---- operators with the NaN rules ---------------------------------------------------------

    private static double divide(double a, double b) {
        return b == 0 ? Double.NaN : a / b;
    }

    /** Python's floored modulo; zero divisor gives NaN. */
    private static double modulo(double a, double b) {
        return b == 0 ? Double.NaN : a - b * Math.floor(a / b);
    }

    private static double where(double cond, double a, double b) {
        return Double.isNaN(cond) ? Double.NaN : cond != 0 ? a : b;
    }

    // ---- parser -------------------------------------------------------------------------------

    private static final class Parser {
        private final String text;
        private final Set<String> knownBands;
        private final List<String> bandOrder = new ArrayList<>();
        private int pos;

        Parser(String text, Set<String> knownBands) {
            this.text = text;
            this.knownBands = knownBands;
        }

        Node parseFormula() {
            Node node = expression();
            skipSpaces();
            if (pos < text.length()) throw error("Unexpected '" + text.charAt(pos) + "'");
            return node;
        }

        private Node expression() {
            Node left = term();
            while (true) {
                if (accept('+')) {
                    Node a = left, b = term();
                    left = v -> a.eval(v) + b.eval(v);
                } else if (accept('-')) {
                    Node a = left, b = term();
                    left = v -> a.eval(v) - b.eval(v);
                } else {
                    return left;
                }
            }
        }

        private Node term() {
            Node left = unary();
            while (true) {
                if (peekPower()) return left;
                if (accept('*')) {
                    Node a = left, b = unary();
                    left = v -> a.eval(v) * b.eval(v);
                } else if (accept('/')) {
                    Node a = left, b = unary();
                    left = v -> divide(a.eval(v), b.eval(v));
                } else if (accept('%')) {
                    Node a = left, b = unary();
                    left = v -> modulo(a.eval(v), b.eval(v));
                } else {
                    return left;
                }
            }
        }

        private Node unary() {
            if (accept('-')) {
                Node a = unary();
                return v -> -a.eval(v);
            }
            if (accept('+')) return unary();
            return power();
        }

        private Node power() {
            Node base = primary();
            if (peekPower()) {
                pos += 2;
                Node a = base, b = unary();
                return v -> Math.pow(a.eval(v), b.eval(v));
            }
            return base;
        }

        private Node primary() {
            skipSpaces();
            if (pos >= text.length()) throw error("Unexpected end of formula");
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = expression();
                expect(')');
                return inner;
            }
            if (Character.isDigit(c) || c == '.') return number();
            if (Character.isLetter(c) || c == '_') return identifier();
            throw error("Unexpected '" + c + "'");
        }

        private Node number() {
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos - start == 1 && text.charAt(start) == '.') throw error(start, "A number needs digits around '.'");
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                int mark = pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                int digits = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                if (pos == digits) throw error(mark, "Malformed exponent in number");
            }
            if (pos < text.length() && (Character.isLetter(text.charAt(pos)) || text.charAt(pos) == '_')) {
                throw error("A number cannot be followed by a name");
            }
            double value = Double.parseDouble(text.substring(start, pos));
            return v -> value;
        }

        private Node identifier() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
            String name = text.substring(start, pos);
            skipSpaces();
            boolean call = pos < text.length() && text.charAt(pos) == '(';
            if (call) return function(name, start);
            if (FUNCTIONS.containsKey(name)) throw error(start, "Function '" + name + "' needs arguments in parentheses");
            if (!knownBands.contains(name)) {
                throw error(start, "Unknown band '" + name + "'; known bands: "
                        + String.join(", ", new TreeSet<>(knownBands)));
            }
            int index = bandOrder.indexOf(name);
            if (index < 0) {
                index = bandOrder.size();
                bandOrder.add(name);
            }
            int i = index;
            return v -> v[i];
        }

        private Node function(String name, int start) {
            Integer arity = FUNCTIONS.get(name);
            if (arity == null) {
                throw error(start, "Unknown function '" + name + "'; allowed: "
                        + String.join(", ", new TreeSet<>(FUNCTIONS.keySet())));
            }
            expect('(');
            List<Node> args = new ArrayList<>();
            args.add(expression());
            while (accept(',')) args.add(expression());
            expect(')');
            if (args.size() != arity) {
                throw error(start, "Function '" + name + "' takes " + arity + " argument(s), got " + args.size());
            }
            Node a = args.get(0), b = arity > 1 ? args.get(1) : null, c = arity > 2 ? args.get(2) : null;
            return switch (name) {
                case "sqrt" -> v -> Math.sqrt(a.eval(v));
                case "abs" -> v -> Math.abs(a.eval(v));
                case "log" -> v -> Math.log(a.eval(v));
                case "log10" -> v -> Math.log10(a.eval(v));
                case "exp" -> v -> Math.exp(a.eval(v));
                case "min" -> v -> Math.min(a.eval(v), b.eval(v));
                case "max" -> v -> Math.max(a.eval(v), b.eval(v));
                case "power" -> v -> Math.pow(a.eval(v), b.eval(v));
                case "clip" -> v -> Math.min(Math.max(a.eval(v), b.eval(v)), c.eval(v));
                case "where" -> v -> where(a.eval(v), b.eval(v), c.eval(v));
                default -> throw new IllegalStateException(name);
            };
        }

        private boolean peekPower() {
            skipSpaces();
            return text.startsWith("**", pos);
        }

        private boolean accept(char c) {
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!accept(c)) {
                throw error(pos >= text.length() ? "Expected '" + c + "' but the formula ended"
                        : "Expected '" + c + "' but found '" + text.charAt(pos) + "'");
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private IllegalArgumentException error(String message) {
            return error(pos, message);
        }

        private IllegalArgumentException error(int at, String message) {
            return new IllegalArgumentException(message + " at position " + (at + 1) + " in '" + text + "'");
        }
    }
}
