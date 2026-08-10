package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SafeX's modern X 12.7.1 model/renderer classifier.
 *
 * Why this exists instead of relying on a network response hook:
 * X can render a UrtTimelinePost from network responses, normalized caches,
 * its local DB, search/detail timelines, modules, quoted posts, and reposts.
 * By inspecting the final modern model immediately before Compose renders it,
 * all of those ingress paths converge here.
 *
 * The class intentionally uses reflection so the extension does not need a
 * compile-time dependency on X's private com.x.models classes.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class SafeXModern {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_DIAGNOSTIC_LINES = 600;
    private static final String DIAGNOSTIC_FILE = "SafeX-Diagnostics.txt";

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_FIELDS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicInteger diagnosticLines = new AtomicInteger();
    private static volatile boolean diagnosticsInitialized;

    private static final class Candidate {
        final long id;
        final Object identity;
        final LinkedHashSet<String> textParts = new LinkedHashSet<>();
        final LinkedHashSet<String> sensitiveReasons = new LinkedHashSet<>();
        boolean xSensitive;

        Candidate(long id, Object identity) {
            this.id = id;
            this.identity = identity;
        }

        void addText(String text) {
            if (text != null) {
                String trimmed = text.trim();
                if (!trimmed.isEmpty()) textParts.add(trimmed);
            }
        }

        void sensitive(String reason) {
            xSensitive = true;
            if (reason != null && !reason.isEmpty()) sensitiveReasons.add(reason);
        }

        String classifierText() {
            StringBuilder out = new StringBuilder();
            for (String part : textParts) {
                if (out.length() > 0) out.append(' ');
                out.append(part);
            }
            return out.toString();
        }
    }

    private static final class Scan {
        final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

        Candidate getOrCreate(Object obj) {
            if (obj == null) return null;
            long id = readPostId(obj);
            String key = id != 0L ? "id:" + id : "obj:" + System.identityHashCode(obj);
            Candidate candidate = candidates.get(key);
            if (candidate == null) {
                candidate = new Candidate(id, obj);
                candidates.put(key, candidate);
            }
            return candidate;
        }
    }

    /**
     * Called by the modern URT Compose hooks for top-level and module items.
     */
    public static boolean shouldRemoveTimelineItem(Object timelineItem) {
        if (timelineItem == null) return false;
        SafeXRuntime.enable();

        try {
            Scan scan = new Scan();

            Object postResult = call(timelineItem, "getPostResult");
            if (postResult == null) {
                // The generic renderer also sees non-post UrtTimelineItems.
                // Only inspect an object directly when it itself looks like a
                // modern post result.
                String className = timelineItem.getClass().getName();
                if (className.contains("ContextualPost")
                        || className.contains("CanonicalPost")
                        || className.contains("RePostedPost")) {
                    postResult = timelineItem;
                } else {
                    return false;
                }
            }

            inspectPostResult(postResult, scan, 0, null);
            if (scan.candidates.isEmpty()) return false;

            boolean blocked = false;
            String blockReason = "";

            // First evaluate every concrete post. This includes quoted/reposted
            // children. If any child is NSFW, the complete visible parent post
            // is removed as requested.
            for (Candidate candidate : scan.candidates.values()) {
                boolean thisBlocked = SafeXRuntime.classifyPost(
                        candidate.id,
                        candidate.classifierText(),
                        candidate.xSensitive
                );
                if (thisBlocked) {
                    blocked = true;
                    if (!candidate.sensitiveReasons.isEmpty()) {
                        blockReason = candidate.sensitiveReasons.toString();
                    } else {
                        blockReason = "manual-or-learned";
                    }
                    break;
                }
            }

            // Safe evidence is committed only when the whole visible post,
            // including children, survives. This prevents a safe parent from
            // being trained as negative while its quoted child caused a block.
            if (!blocked) {
                for (Candidate candidate : scan.candidates.values()) {
                    SafeXRuntime.observeSafePost(candidate.id, candidate.classifierText());
                }
            }

            writeDiagnostic(timelineItem, scan, blocked, blockReason);
            return blocked;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    private static void inspectPostResult(
            Object postResult,
            Scan scan,
            int depth,
            String inheritedSensitiveReason
    ) {
        if (postResult == null || depth > MAX_DEPTH || scan.visited.contains(postResult)) return;
        scan.visited.add(postResult);

        String name = postResult.getClass().getName();

        if (name.endsWith("CanonicalPost")) {
            Candidate candidate = inspectConcretePost(postResult, scan);
            if (candidate != null && inheritedSensitiveReason != null) {
                candidate.sensitive(inheritedSensitiveReason);
            }
            return;
        }

        if (name.endsWith("ContextualPost")) {
            Object canonical = call(postResult, "getCanonicalPost");
            Candidate candidate = canonical != null
                    ? inspectConcretePost(canonical, scan)
                    : inspectConcretePost(postResult, scan);

            // This is the exact modern signal used by X's sensitive-media
            // renderer: ContextualPost -> MediaVisibilityResults ->
            // BlurImageInterstitial. It also covers age-verification prompts.
            Object visibility = call(postResult, "getMediaVisibilityResults");
            Object blur = visibility == null ? null : call(visibility, "getBlurImageInterstitial");
            if (candidate != null && blur != null) {
                candidate.sensitive("blur-image-interstitial");
            }

            if (candidate != null && inheritedSensitiveReason != null) {
                candidate.sensitive(inheritedSensitiveReason);
            }

            // Defensive extra signal for model variants that expose sensitive
            // media categories directly on attachments.
            if (candidate != null && hasSensitiveMediaCategories(call(postResult, "getMedia"), 0,
                    Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()))) {
                candidate.sensitive("sensitive-media-categories");
            }

            inspectPostResult(call(postResult, "getDisplayQuotedPost"), scan, depth + 1, null);
            inspectPostResult(readField(postResult, "quotedPost"), scan, depth + 1, null);
            inspectPostResult(call(postResult, "getRePostedPost"), scan, depth + 1, null);
            return;
        }

        if (name.endsWith("RePostedPost")) {
            Object canonical = call(postResult, "getCanonicalPost");
            Candidate candidate = canonical != null
                    ? inspectConcretePost(canonical, scan)
                    : inspectConcretePost(postResult, scan);
            if (candidate != null && inheritedSensitiveReason != null) {
                candidate.sensitive(inheritedSensitiveReason);
            }
            inspectPostResult(call(postResult, "getQuotedPost"), scan, depth + 1, null);
            return;
        }

        // Future/minor model variants: use capabilities instead of exact class
        // names. This is deliberately narrow so unrelated objects are not
        // classified as posts.
        Object canonical = call(postResult, "getCanonicalPost");
        if (canonical != null) {
            Candidate candidate = inspectConcretePost(canonical, scan);
            if (candidate != null && inheritedSensitiveReason != null) {
                candidate.sensitive(inheritedSensitiveReason);
            }
            inspectPostResult(call(postResult, "getDisplayQuotedPost"), scan, depth + 1, null);
            inspectPostResult(call(postResult, "getQuotedPost"), scan, depth + 1, null);
            inspectPostResult(call(postResult, "getRePostedPost"), scan, depth + 1, null);
        }
    }

    private static Candidate inspectConcretePost(Object post, Scan scan) {
        if (post == null) return null;
        Candidate candidate = scan.getOrCreate(post);
        if (candidate == null) return null;

        candidate.addText(asString(call(post, "getText")));
        appendEntities(candidate, call(post, "getEntityList"));

        Boolean possiblySensitive = asBoolean(call(post, "isPossiblySensitive"));
        if (Boolean.TRUE.equals(possiblySensitive)) {
            candidate.sensitive("isPossiblySensitive");
        }

        if (hasSensitiveMediaCategories(call(post, "getMedia"), 0,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()))) {
            candidate.sensitive("sensitive-media-categories");
        }

        return candidate;
    }

    private static void appendEntities(Candidate candidate, Object entityList) {
        if (candidate == null || entityList == null) return;

        for (Object hashtag : iterable(call(entityList, "getHashtags"))) {
            String text = asString(call(hashtag, "getText"));
            if (text != null && !text.isEmpty()) candidate.addText("#" + text);
        }

        for (Object url : iterable(call(entityList, "getUrls"))) {
            String expanded = asString(call(url, "getExpandedUrl"));
            if (expanded == null || expanded.isEmpty()) expanded = asString(call(url, "getUrl"));
            candidate.addText(expanded);
        }
    }

    /**
     * Some X model families expose a Set of server-provided sensitive media
     * categories. Walk small collection/wrapper graphs defensively.
     */
    private static boolean hasSensitiveMediaCategories(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 4 || visited.contains(value)) return false;
        visited.add(value);

        Object categories = call(value, "getSensitiveMediaCategories");
        if (categories instanceof Collection && !((Collection) categories).isEmpty()) return true;
        if (categories instanceof Iterable && ((Iterable) categories).iterator().hasNext()) return true;

        if (value instanceof Iterable) {
            for (Object child : (Iterable) value) {
                if (hasSensitiveMediaCategories(child, depth + 1, visited)) return true;
            }
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (hasSensitiveMediaCategories(Array.get(value, i), depth + 1, visited)) return true;
            }
        }

        // Small set of known media wrapper getter names; do not recursively
        // traverse arbitrary object fields.
        String[] wrappers = {"getMedia", "getAttachment", "getMediaAttachment", "getImage", "getVideo"};
        for (String wrapper : wrappers) {
            Object child = call(value, wrapper);
            if (child != null && child != value
                    && hasSensitiveMediaCategories(child, depth + 1, visited)) return true;
        }
        return false;
    }

    private static long readPostId(Object post) {
        if (post == null) return 0L;
        try {
            Object identifier = call(post, "getId");
            if (identifier == null) return 0L;
            Object value = call(identifier, "getValue");
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static Iterable iterable(Object value) {
        if (value instanceof Iterable) return (Iterable) value;
        if (value != null && value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) out.add(Array.get(value, i));
            return out;
        }
        return Collections.emptyList();
    }

    private static Object call(Object target, String methodName) {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + methodName;
        if (MISSING_METHODS.contains(key)) return null;

        try {
            Method method = METHOD_CACHE.get(key);
            if (method == null) {
                method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                METHOD_CACHE.put(key, method);
            }
            return method.invoke(target);
        } catch (NoSuchMethodException e) {
            MISSING_METHODS.add(key);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + fieldName;
        if (MISSING_FIELDS.contains(key)) return null;

        try {
            Field field = FIELD_CACHE.get(key);
            if (field == null) {
                field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(key, field);
            }
            return field.get(target);
        } catch (NoSuchFieldException e) {
            MISSING_FIELDS.add(key);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static void writeDiagnostic(Object timelineItem, Scan scan, boolean blocked, String reason) {
        try {
            int line = diagnosticLines.getAndIncrement();
            if (line >= MAX_DIAGNOSTIC_LINES) return;

            if (!diagnosticsInitialized) {
                synchronized (SafeXModern.class) {
                    if (!diagnosticsInitialized) {
                        PikoUtils.pikoWriteFile(DIAGNOSTIC_FILE,
                                "SafeX v0.4 diagnostics\n", false);
                        diagnosticsInitialized = true;
                    }
                }
            }

            int xSensitiveCount = 0;
            StringBuilder ids = new StringBuilder();
            StringBuilder reasons = new StringBuilder();
            for (Candidate candidate : scan.candidates.values()) {
                if (ids.length() > 0) ids.append(',');
                ids.append(candidate.id);
                if (candidate.xSensitive) xSensitiveCount++;
                if (!candidate.sensitiveReasons.isEmpty()) {
                    if (reasons.length() > 0) reasons.append('|');
                    reasons.append(candidate.sensitiveReasons);
                }
            }

            String data = "item=" + timelineItem.getClass().getName()
                    + " ids=" + ids
                    + " candidates=" + scan.candidates.size()
                    + " xSensitive=" + xSensitiveCount
                    + " blocked=" + blocked
                    + " reason=" + reason
                    + " signals=" + reasons
                    + "\n";
            PikoUtils.pikoWriteFile(DIAGNOSTIC_FILE, data, true);
        } catch (Throwable ignored) {
        }
    }

    private SafeXModern() {}
}
