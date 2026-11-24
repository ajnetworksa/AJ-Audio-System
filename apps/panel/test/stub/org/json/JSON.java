package org.json;
import java.util.*;
/** test-only minimal org.json (Android semantics for opt*) */
final class JSON {
    static Object parse(String s) throws JSONException { P p = new P(s); Object v = p.val(); p.ws(); if (p.i < s.length()) throw new JSONException("trailing at " + p.i); return v; }
    static final class P {
        final String s; int i; P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object val() throws JSONException {
            ws(); if (i >= s.length()) throw new JSONException("eof");
            char c = s.charAt(i);
            if (c == '{') { i++; JSONObject o = new JSONObject(); ws(); if (s.charAt(i) == '}') { i++; return o; }
                while (true) { ws(); String k = (String) str(); ws(); if (s.charAt(i++) != ':') throw new JSONException(": expected"); o.m.put(k, val()); ws(); char d = s.charAt(i++); if (d == '}') return o; if (d != ',') throw new JSONException(", expected at " + i); } }
            if (c == '[') { i++; JSONArray a = new JSONArray(); ws(); if (s.charAt(i) == ']') { i++; return a; }
                while (true) { a.l.add(val()); ws(); char d = s.charAt(i++); if (d == ']') return a; if (d != ',') throw new JSONException(", expected"); } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i); if (n.isEmpty()) throw new JSONException("bad value at " + st);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            long l = Long.parseLong(n); if (l == (int) l) return (int) l; return l;
        }
        Object str() throws JSONException {
            if (s.charAt(i) != '"') throw new JSONException("string expected at " + i); i++;
            StringBuilder b = new StringBuilder();
            while (true) { char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++); switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); } }
                else b.append(c); }
        }
    }
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) { switch (c) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break; case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
            default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } }
        return b.append('"').toString();
    }
    static String str(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return quote((String) v);
        if (v instanceof Double) { double d = (Double) v; if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d); return String.valueOf(d); }
        return v.toString();
    }
    static Double dbl(Object v) { if (v instanceof Number) return ((Number) v).doubleValue(); if (v instanceof String) try { return Double.valueOf((String) v); } catch (Exception e) { } return null; }
    static Boolean bool(Object v) { if (v instanceof Boolean) return (Boolean) v; if (v instanceof String) { if (((String) v).equalsIgnoreCase("true")) return true; if (((String) v).equalsIgnoreCase("false")) return false; } return null; }
    static String tostr(Object v) { if (v == null) return null; if (v instanceof String) return (String) v; return v == JSONObject.NULL ? "null" : (v instanceof Double ? str(v) : String.valueOf(v)); }
}
