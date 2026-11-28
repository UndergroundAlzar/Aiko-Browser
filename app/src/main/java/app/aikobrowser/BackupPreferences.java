package app.aikobrowser;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;

/** Typed Android preferences snapshot shared by the Android and host migration paths. */
final class BackupPreferences {
    static final String BABEL = "shared_prefs/babel_config.xml";
    static final String SHELF = "shared_prefs/reading_shelf.xml";
    static final String BODIES = "files/reading_shelf/";
    private BackupPreferences() {}

    static Map<String,Object> read(byte[] xml) throws IOException {
        String source = new String(xml, StandardCharsets.UTF_8);
        // A character stream makes the UTF-8 precheck independent of the XML declaration.
        if (source.contains("<!DOCTYPE")) throw new IOException("设置文件包含不支持的 XML 声明");
        PrefHandler handler = new PrefHandler();
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(new InputSource(new StringReader(source)), handler);
        } catch (Exception invalid) { throw new IOException("设置 XML 已损坏或类型不受支持"); }
        return handler.values;
    }

    private static final class PrefHandler extends DefaultHandler {
        final Map<String,Object> values = new LinkedHashMap<>();
        int depth;
        String key, scalar;
        Set<String> set;
        StringBuilder text;
        @Override public InputSource resolveEntity(String pub, String sys) throws SAXException { throw new SAXException(); }
        @Override public void startElement(String uri, String local, String tag, Attributes attrs) throws SAXException {
            if (++depth == 1 && "map".equals(tag)) return;
            if (depth == 2) {
                key = attrs.getValue("name");
                if (key == null || values.containsKey(key) || values.size() >= 65536) throw new SAXException();
                if ("set".equals(tag)) { set = new LinkedHashSet<>(); return; }
                scalar = attrs.getValue("value");
                text = new StringBuilder();
            } else if (depth == 3 && set != null && "string".equals(tag) && set.size() < 65536) {
                text = new StringBuilder();
            } else throw new SAXException();
        }
        @Override public void characters(char[] buf, int start, int count) throws SAXException {
            if (text != null) text.append(buf, start, count);
            else for (int i = start; i < start + count; i++) if (!Character.isWhitespace(buf[i])) throw new SAXException();
        }
        @Override public void endElement(String uri, String local, String tag) throws SAXException {
            if (depth == 3) { set.add(text.toString()); text = null; }
            if (depth == 2) {
                Object value;
                switch (tag) {
                    case "set": value = set; set = null; break;
                    case "string": value = text.toString(); break;
                    case "int": value = Integer.valueOf(scalar); break;
                    case "long": value = Long.valueOf(scalar); break;
                    case "float": value = Float.valueOf(scalar); break;
                    case "boolean":
                        if (!"true".equals(scalar) && !"false".equals(scalar)) throw new SAXException();
                        value = Boolean.valueOf(scalar); break;
                    default: throw new SAXException();
                }
                values.put(key, value);
                text = null;
            }
            depth--;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\r", "&#13;");
    }

    static byte[] encode(Map<String,?> values) throws IOException {
        StringBuilder out = new StringBuilder("<map>");
        for (Map.Entry<String,?> pair : values.entrySet()) {
            String attr = " name=\"" + escape(pair.getKey()).replace("\n", "&#10;").replace("\t", "&#9;") + "\"";
            Object v = pair.getValue();
            if (v instanceof String) out.append("<string").append(attr).append('>').append(escape((String)v)).append("</string>");
            else if (v instanceof Set) {
                out.append("<set").append(attr).append('>');
                for (Object s : (Set<?>)v) out.append("<string>").append(escape((String)s)).append("</string>");
                out.append("</set>");
            } else {
                String type;
                if (v instanceof Integer) type = "int";
                else if (v instanceof Long) type = "long";
                else if (v instanceof Float) type = "float";
                else if (v instanceof Boolean) type = "boolean";
                else throw new IOException("设置类型不受支持");
                out.append('<').append(type).append(attr).append(" value=\"").append(v).append("\"/>");
            }
        }
        return out.append("</map>").toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static void replace(SharedPreferences prefs, Map<String,?> values) throws IOException {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String,?> item : values.entrySet()) {
            String key = item.getKey(); Object v = item.getValue();
            if (v instanceof String) edit.putString(key, (String)v);
            else if (v instanceof Integer) edit.putInt(key, (Integer)v);
            else if (v instanceof Long) edit.putLong(key, (Long)v);
            else if (v instanceof Float) edit.putFloat(key, (Float)v);
            else if (v instanceof Boolean) edit.putBoolean(key, (Boolean)v);
            else if (v instanceof Set) edit.putStringSet(key, (Set<String>)v);
            else throw new IOException("设置类型不受支持");
        }
        if (!edit.commit()) throw new IOException("写入设置失败");
    }

    static void portableBabel(Map<String,Object> values, Map<String,?> destination) throws IOException {
        if (!values.containsKey("config_json")) return;
        try {
            JSONObject root = new JSONObject((String)values.get("config_json"));
            JSONArray profiles = root.has("aiProfiles") ? root.getJSONArray("aiProfiles") : new JSONArray();
            JSONArray local = null;
            if (destination != null && destination.get("config_json") instanceof String) {
                local = new JSONObject((String)destination.get("config_json")).optJSONArray("aiProfiles");
            }
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.getJSONObject(i);
                p.remove("apiKey");
                p.remove("extraHeadersJson"); // Arbitrary custom headers can carry any credential.
                if (local == null) continue;
                for (int j = 0; j < local.length(); j++) {
                    JSONObject old = local.getJSONObject(j);
                    if (matchesProfile(p, old)) {
                        for (String field : new String[]{"apiKey", "extraHeadersJson"}) {
                            if (old.has(field)) p.put(field, old.getString(field));
                        }
                        break;
                    }
                }
            }
            values.put("config_json", root.toString());
        } catch (JSONException | ClassCastException invalid) { throw new IOException("巴别塔配置无法读取"); }
    }

    private static boolean matchesProfile(JSONObject p, JSONObject old) {
        for (String field : new String[]{"id", "apiFormat", "baseUrl"}) {
            String value = p.optString(field, "");
            if (value.isEmpty() || !value.equals(old.optString(field, ""))) return false;
        }
        return true;
    }

    interface ArchivePath { String resolve(String filename) throws IOException; }

    static void rebaseShelf(Map<String,Object> values, ArchivePath resolve) throws IOException {
        if (!values.containsKey("items")) return;
        try {
            JSONArray items = new JSONArray((String)values.get("items"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject entry = items.getJSONObject(i);
                String source = entry.optString("a", "").replace('\\', '/');
                String name = source.substring(source.lastIndexOf('/') + 1);
                entry.remove("a");
                if (!safeArchiveName(name)) continue;
                String path = resolve.resolve(name);
                if (path != null) entry.put("a", path);
            }
            values.put("items", items.toString());
        } catch (JSONException | ClassCastException invalid) { throw new IOException("书架索引无法读取"); }
    }

    static boolean safeArchiveName(String filename) {
        return filename.matches("(?:[0-9a-f]{32}|[0-9a-f]{1,8})\\.mht");
    }
}
