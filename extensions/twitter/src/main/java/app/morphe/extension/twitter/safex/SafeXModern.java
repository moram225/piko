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

/**
 * SafeX's modern X 12.7.1 model classifier.
 *
 * v0.6 evaluates the concrete modern post model before the generic URT list is
 * emitted. Search additionally supplies the raw query as context. That query is
 * never learned from; it is used only as a local safety fallback when X returns
 * visual media for a clearly high-risk search while its own sensitivity flags
 * are false/missing.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class SafeXModern {
    private static final int MAX_DEPTH = 8;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_FIELDS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final class Candidate {
        final long id;
        final Object identity;
        final LinkedHashSet<String> textParts = new LinkedHashSet<>();
        final LinkedHashSet<String> sensitiveReasons = new LinkedHashSet<>();
        boolean xSensitive;
        boolean hasVisualMedia;

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

    public static boolean shouldRemoveTimelineItem(Object timelineItem) {
        return shouldRemoveTimelineItem(timelineItem, "");
    }

    /**
     * Evaluate one visible timeline post, including quoted/reposted children.
     *
     * Order matters:
     *  1) X native sensitivity / persistent manual blocks / learned content;
     *  2) only if still allowed, high-risk Search query + visual media fallback;
     *  3) weak-safe evidence only when the complete visible post survives.
     *
     * The Search fallback is deliberately NOT a positive training source.
     */
    public static boolean shouldRemoveTimelineItem(Object timelineItem, String searchQuery) {
        if (timelineItem == null) return false;
        SafeXRuntime.enable();

        try {
            Scan scan = new Scan();

            Object postResult = call(timelineItem, "getPostResult");
            if (postResult == null) {
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

            for (Candidate candidate : scan.candidates.values()) {
                if (SafeXRuntime.classifyPost(
                        candidate.id,
                        candidate.classifierText(),
                        candidate.xSensitive
                )) {
                    blocked = true;
                    break;
                }
            }

            // Search can return image/video posts whose visible tweet text does
            // not contain the query and whose server sensitive flags are false.
            // For a clearly high-risk query, do not let such visual media leak.
            // Query context itself is never persisted as training evidence.
            if (!blocked && SafeXRuntime.isHighRiskSearchQuery(searchQuery)) {
                for (Candidate candidate : scan.candidates.values()) {
                    if (candidate.hasVisualMedia) {
                        blocked = true;
                        break;
                    }
                }
            }

            if (!blocked) {
                for (Candidate candidate : scan.candidates.values()) {
                    SafeXRuntime.observeSafePost(candidate.id, candidate.classifierText());
                }
            }

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

            // X's modern visibility path. A blur interstitial includes native
            // sensitive-media and age-verification prompts.
            Object visibility = call(postResult, "getMediaVisibilityResults");
            Object blur = visibility == null ? null : call(visibility, "getBlurImageInterstitial");
            if (candidate != null && blur != null) {
                candidate.sensitive("blur-image-interstitial");
            }

            // Tweet-level interstitials are also safety/context signals. Do not
            // treat every generic interstitial as sexual by itself; instead use
            // its display/reveal text as classifier input.
            Object tweetInterstitial = call(postResult, "getTweetInterstitial");
            if (candidate != null && tweetInterstitial != null) {
                candidate.addText(asString(call(tweetInterstitial, "getDisplayText")));
                candidate.addText(asString(call(tweetInterstitial, "getRevealText")));
            }

            if (candidate != null && inheritedSensitiveReason != null) {
                candidate.sensitive(inheritedSensitiveReason);
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

        Object media = call(post, "getMedia");
        candidate.hasVisualMedia |= containsMedia(media);
        appendMediaMetadata(candidate, media, 0,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));

        if (hasSensitiveMediaCategories(media, 0,
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
     * Add only media-local descriptive metadata. Never add author/account data.
     */
    private static void appendMediaMetadata(Candidate candidate, Object value, int depth, Set<Object> visited) {
        if (candidate == null || value == null || depth > 4 || visited.contains(value)) return;
        visited.add(value);

        candidate.addText(asString(call(value, "getAltText")));
        candidate.addText(asString(call(value, "getGrokTag")));
        candidate.addText(asString(call(value, "getOriginalFilename")));

        if (value instanceof Iterable) {
            for (Object child : (Iterable) value) {
                appendMediaMetadata(candidate, child, depth + 1, visited);
            }
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendMediaMetadata(candidate, Array.get(value, i), depth + 1, visited);
            }
        }

        String[] wrappers = {"getMedia", "getAttachment", "getMediaAttachment", "getImage", "getVideo"};
        for (String wrapper : wrappers) {
            Object child = call(value, wrapper);
            if (child != null && child != value) {
                appendMediaMetadata(candidate, child, depth + 1, visited);
            }
        }
    }

    private static boolean containsMedia(Object value) {
        if (value == null) return false;
        if (value instanceof Collection) return !((Collection) value).isEmpty();
        if (value instanceof Iterable) return ((Iterable) value).iterator().hasNext();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        return true;
    }

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

    private SafeXModern() {}
}
