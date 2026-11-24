package org.json;
import java.util.*;
public class JSONArray {
    final ArrayList<Object> l = new ArrayList<>();
    public JSONArray() { }
    public JSONArray(String s) throws JSONException { Object v = JSON.parse(s); if (!(v instanceof JSONArray)) throw new JSONException("not an array"); l.addAll(((JSONArray) v).l); }
    public int length() { return l.size(); }
    public JSONArray put(Object v) { l.add(v == null ? JSONObject.NULL : v); return this; }
    public JSONArray put(int v) { l.add(v); return this; }
    public Object opt(int i) { return i < 0 || i >= l.size() ? null : l.get(i); }
    public String optString(int i) { String s = JSON.tostr(opt(i)); return s == null ? "" : s; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONObject getJSONObject(int i) throws JSONException { Object v = opt(i); if (v instanceof JSONObject) return (JSONObject) v; throw new JSONException("not object at " + i); }
    public double optDouble(int i, double d) { Double v = JSON.dbl(opt(i)); return v == null ? d : v; }
    public int optInt(int i) { Double v = JSON.dbl(opt(i)); return v == null ? 0 : v.intValue(); }
    @Override public String toString() { StringBuilder b = new StringBuilder("["); for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(','); b.append(JSON.str(l.get(i))); } return b.append(']').toString(); }
}
