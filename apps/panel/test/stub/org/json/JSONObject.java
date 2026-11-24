package org.json;
import java.util.*;
public class JSONObject {
    public static final Object NULL = new Object() { public String toString() { return "null"; } public boolean equals(Object o) { return o == this || o == null; } };
    final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    public JSONObject() { }
    public JSONObject(String s) throws JSONException { Object v = JSON.parse(s); if (!(v instanceof JSONObject)) throw new JSONException("not an object"); m.putAll(((JSONObject) v).m); }
    public JSONObject put(String k, Object v) throws JSONException { if (k == null) throw new JSONException("null key"); if (v == null) m.remove(k); else m.put(k, v); return this; }
    public JSONObject put(String k, int v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, long v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, double v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, boolean v) throws JSONException { return put(k, (Object) v); }
    public Object opt(String k) { return k == null ? null : m.get(k); }
    public Object get(String k) throws JSONException { Object v = m.get(k); if (v == null) throw new JSONException("no " + k); return v; }
    public boolean has(String k) { return m.containsKey(k); }
    public boolean isNull(String k) { Object v = m.get(k); return v == null || v == NULL; }
    public Object remove(String k) { return m.remove(k); }
    public int length() { return m.size(); }
    public Iterator<String> keys() { return new ArrayList<>(m.keySet()).iterator(); }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { String s = JSON.tostr(opt(k)); return s != null ? s : d; }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { Double v = JSON.dbl(opt(k)); return v == null ? d : v.intValue(); }
    public long optLong(String k, long d) { Double v = JSON.dbl(opt(k)); return v == null ? d : v.longValue(); }
    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double d) { Double v = JSON.dbl(opt(k)); return v == null ? d : v; }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean d) { Boolean v = JSON.bool(opt(k)); return v == null ? d : v; }
    public JSONObject optJSONObject(String k) { Object v = opt(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = opt(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject getJSONObject(String k) throws JSONException { Object v = get(k); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("not object " + k); }
    public String getString(String k) throws JSONException { return JSON.tostr(get(k)); }
    @Override public String toString() { StringBuilder b = new StringBuilder("{"); boolean f = true; for (Map.Entry<String, Object> e : m.entrySet()) { if (!f) b.append(','); f = false; b.append(JSON.quote(e.getKey())).append(':').append(JSON.str(e.getValue())); } return b.append('}').toString(); }
    public String toString(int indent) { return toString(); }
}
