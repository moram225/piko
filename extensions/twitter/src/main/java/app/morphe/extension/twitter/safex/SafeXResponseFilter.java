package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Filters X JSON responses before Jackson deserializes them.
 *
 * X 12.7.1's canonical post GraphQL fragment contains both:
 *   legacy.possibly_sensitive
 *   details.full_text / hashtag_entities
 *
 * SafeX uses those server-provided values without mutating X's sensitive-media
 * warning/age-verification models. If a blocked post lives inside an URT entry
 * (entry_id/entryId), that complete entry is removed from the response array.
 */
@SuppressWarnings("unused")
public final class SafeXResponseFilter {
    private static final int MAX_JSON_BYTES = 12 * 1024 * 1024;

    private static final class Observation {
        final long postId;
        final String classifierText;
        final boolean xSensitive;
        final boolean blocked;

        Observation(long postId, String classifierText, boolean xSensitive, boolean blocked) {
            this.postId = postId;
            this.classifierText = classifierText;
            this.xSensitive = xSensitive;
            this.blocked = blocked;
        }
    }

    private static final class NodeResult {
        boolean blocked;
        boolean changed;
        final List<Observation> observations = new ArrayList<>();

        void merge(NodeResult other) {
            blocked |= other.blocked;
            changed |= other.changed;
            observations.addAll(other.observations);
        }
    }

    public static InputStream filterInputStream(InputStream inputStream) {
        if (inputStream == null) return null;

        try {
            SafeXRuntime.enable();

            byte[] original = readAll(inputStream);
            if (original.length == 0 || original.length > MAX_JSON_BYTES) {
                return new ByteArrayInputStream(original);
            }

            String json = new String(original, StandardCharsets.UTF_8);

            // Avoid parsing unrelated JSON. X timeline/search responses include
            // possibly_sensitive for canonical posts, including false values.
            if (!json.contains("\"possibly_sensitive\"") || !json.contains("\"rest_id\"")) {
                return new ByteArrayInputStream(original);
            }

            String trimmed = json.trim();
            Object root;
            if (trimmed.startsWith("{")) {
                root = new JSONObject(trimmed);
            } else if (trimmed.startsWith("[")) {
                root = new JSONArray(trimmed);
            } else {
                return new ByteArrayInputStream(original);
            }

            NodeResult result = walk(root);
            if (!result.changed) {
                return new ByteArrayInputStream(original);
            }

            byte[] filtered = root.toString().getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(filtered);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            // Never damage X networking because our parser failed. The caller
            // receives a replacement stream whenever possible.
            try {
                return inputStream;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static NodeResult walk(Object node) {
        NodeResult out = new NodeResult();

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;

            Observation observation = extractObservation(obj);
            if (observation != null) {
                out.observations.add(observation);
                out.blocked |= observation.blocked;
            }

            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = obj.keys();
            while (iterator.hasNext()) keys.add(iterator.next());

            for (String key : keys) {
                Object child = obj.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    out.merge(walk(child));
                }
            }
            return out;
        }

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;

            // Iterate backwards so removals cannot skip following items.
            for (int i = array.length() - 1; i >= 0; i--) {
                Object item = array.opt(i);
                if (!(item instanceof JSONObject) && !(item instanceof JSONArray)) continue;

                NodeResult child = walk(item);
                boolean boundary = item instanceof JSONObject && isTimelineBoundary((JSONObject) item);

                if (boundary && child.blocked) {
                    array.remove(i);
                    child.changed = true;
                    out.changed = true;
                    // X-sensitive observations are already trained by classify.
                    // Safe observations inside a removed entry must NOT become
                    // weak-negative training evidence.
                    continue;
                }

                if (boundary && !child.blocked) {
                    commitWeakSafe(child.observations);
                    child.observations.clear();
                }

                out.merge(child);
            }
            return out;
        }

        return out;
    }

    private static boolean isTimelineBoundary(JSONObject obj) {
        return obj.has("entry_id") || obj.has("entryId");
    }

    private static void commitWeakSafe(List<Observation> observations) {
        for (Observation observation : observations) {
            if (!observation.xSensitive && !observation.blocked) {
                SafeXRuntime.observeNetworkSafe(observation.postId, observation.classifierText);
            }
        }
    }

    /**
     * Detects canonical/legacy tweet objects. User/account metadata is ignored.
     */
    private static Observation extractObservation(JSONObject obj) {
        JSONObject legacy = obj.optJSONObject("legacy");

        boolean hasSensitiveField = legacy != null && legacy.has("possibly_sensitive");
        if (!hasSensitiveField && !obj.has("possibly_sensitive")) return null;

        boolean xSensitive = hasSensitiveField
                ? booleanValue(legacy.opt("possibly_sensitive"))
                : booleanValue(obj.opt("possibly_sensitive"));

        // Older response shapes can carry category warnings separately.
        xSensitive |= containsSensitiveWarning(obj.optJSONObject("sensitive_media_warning"));
        xSensitive |= containsSensitiveWarning(obj.optJSONObject("ext_sensitive_media_warning"));

        String id = firstNonEmpty(
                obj.optString("rest_id", ""),
                legacy == null ? "" : legacy.optString("id_str", ""),
                obj.optString("id_str", "")
        );
        long postId = parsePostId(id);
        if (postId == 0L) return null;

        String classifierText = buildClassifierText(obj, legacy);
        boolean blocked = SafeXRuntime.classifyNetworkPost(postId, classifierText, xSensitive);
        return new Observation(postId, classifierText, xSensitive, blocked);
    }

    private static boolean containsSensitiveWarning(JSONObject warning) {
        if (warning == null) return false;
        Iterator<String> keys = warning.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = warning.opt(key);
            if (value instanceof Boolean && (Boolean) value) return true;
            if (value instanceof String && "true".equalsIgnoreCase((String) value)) return true;
        }
        return false;
    }

    private static String buildClassifierText(JSONObject obj, JSONObject legacy) {
        StringBuilder text = new StringBuilder();

        JSONObject details = obj.optJSONObject("details");
        append(text, details == null ? null : details.optString("full_text", null));
        append(text, obj.optString("full_text", null));
        append(text, obj.optString("text", null));
        if (legacy != null) {
            append(text, legacy.optString("full_text", null));
            append(text, legacy.optString("text", null));
        }

        appendHashtags(text, details == null ? null : details.optJSONArray("hashtag_entities"));

        if (legacy != null) {
            JSONObject entities = legacy.optJSONObject("entities");
            if (entities != null) {
                appendHashtags(text, entities.optJSONArray("hashtags"));
                appendUrls(text, entities.optJSONArray("urls"));
            }
        }

        // Canonical entities can occur under several nested response shapes.
        collectExpandedUrls(obj, text, 0);
        return text.toString();
    }

    private static void appendHashtags(StringBuilder out, JSONArray hashtags) {
        if (hashtags == null) return;
        for (int i = 0; i < hashtags.length(); i++) {
            JSONObject item = hashtags.optJSONObject(i);
            if (item == null) continue;
            String tag = firstNonEmpty(item.optString("text", ""), item.optString("tag", ""));
            if (!tag.isEmpty()) append(out, "#" + tag);
        }
    }

    private static void appendUrls(StringBuilder out, JSONArray urls) {
        if (urls == null) return;
        for (int i = 0; i < urls.length(); i++) {
            JSONObject item = urls.optJSONObject(i);
            if (item == null) continue;
            append(out, firstNonEmpty(item.optString("expanded_url", ""), item.optString("url", "")));
        }
    }

    private static void collectExpandedUrls(Object node, StringBuilder out, int depth) {
        if (node == null || depth > 5) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String expanded = obj.optString("expanded_url", "");
            if (!expanded.isEmpty()) append(out, expanded);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                Object child = obj.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectExpandedUrls(child, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectExpandedUrls(child, out, depth + 1);
                }
            }
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long parsePostId(String id) {
        try {
            return Long.parseLong(id);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static void append(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(value);
    }

    private static byte[] readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        try {
            inputStream.close();
        } catch (Throwable ignored) {
        }
        return out.toByteArray();
    }

    private SafeXResponseFilter() {}
}
