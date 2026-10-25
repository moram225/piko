/*
 * SafeX - remove posts that X itself classifies as sensitive, before render.
 *
 * Detection is X's own classification, not a local heuristic:
 *   JsonMediaEntity.q            == "possibly_sensitive"
 *   JsonMediaEntity.u            == "sensitive_media_warning" / "ext_sensitive_media_warning"
 *   JsonSensitiveMediaWarning.a  == "adult_content"
 *   JsonSensitiveMediaWarning.b  == "graphic_violence"
 *   JsonSensitiveMediaWarning.c  == "other"
 *
 * The timeline entry parser is wrapped by beginEntry()/endEntry(), and every
 * media entity parsed on that thread marks the current entry. checkEntry()
 * then returns null, which is the drop path piko already uses for ads: the
 * JSON mapper returns null and the entry never reaches the model or the UI.
 */

package app.morphe.extension.twitter.patches;

import com.twitter.model.json.core.JsonMediaEntity;
import com.twitter.model.json.core.JsonSensitiveMediaWarning;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SafeX {

    /** One-shot runtime diagnostics: proves which hooks X actually executes. */
    private static final AtomicBoolean ENTRY_SEEN = new AtomicBoolean(false);
    private static final AtomicBoolean MEDIA_SEEN = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_Q = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_WARN = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_VIS = new AtomicBoolean(false);
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    /**
     * True once a timeline entry parse has started. When SafeX is active it must
     * see X's original classification flags, so the "Show sensitive media"
     * patch must not clear them (see TimelineEntry.showSensitiveMedia).
     */
    public static volatile boolean active;

    /** Number of entries dropped since process start; diagnostics only. */
    private static final AtomicInteger DROPPED = new AtomicInteger();

    /** Reads and increments the drop counter (never logs post content). */
    public static int recordDrop() {
        return DROPPED.incrementAndGet();
    }

    /** Current drop count, for a settings/diagnostics surface. */
    public static int droppedCount() {
        return DROPPED.get();
    }

    /** Maximum entry nesting we track; deeper frames are ignored, never trusted. */
    private static final int MAX_DEPTH = 32;

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    private static final ThreadLocal<boolean[]> FRAMES = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[MAX_DEPTH];
        }
    };

    private SafeX() {
    }

    /** Called at the very start of a timeline entry / module item parse. */
    public static void beginEntry() {
        active = true;
        if (ENTRY_SEEN.compareAndSet(false, true)) {
            System.err.println("SafeX: entry parse hook active");
        }
        int entries = ENTRY_COUNT.incrementAndGet();
        if (entries % 25 == 0) {
            System.err.println("SafeX: entries parsed=" + entries);
        }
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d < MAX_DEPTH) {
            FRAMES.get()[d] = false;
            depth[0] = d + 1;
        }
    }

    /**
     * Called when an entry parse finishes. Returns true when anything parsed
     * inside that entry was classified sensitive, and pops the frame.
     */
    public static boolean endEntry() {
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            return false;
        }
        depth[0] = d - 1;
        return FRAMES.get()[d - 1];
    }

    /**
     * Called after each media entity is parsed. Marks the innermost entry
     * frame when X flagged the media, using the three classification flags.
     */
    public static void observeMedia(Object media) {
        if (!(media instanceof JsonMediaEntity)) {
            return;
        }
        if (MEDIA_SEEN.compareAndSet(false, true)) {
            System.err.println("SafeX: media parse hook active");
        }
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            // Media parsed outside an entry (e.g. a detail request); ignore so
            // the marker cannot leak into the next timeline entry.
            return;
        }
        JsonMediaEntity entity = (JsonMediaEntity) media;
        boolean sensitive = entity.q;
        if (sensitive && SIGNAL_Q.compareAndSet(false, true)) {
            System.err.println("SafeX: signal possibly_sensitive");
        }
        if (!sensitive) {
            JsonSensitiveMediaWarning warning = entity.u;
            sensitive = warning != null && (warning.a || warning.b || warning.c);
            if (sensitive && SIGNAL_WARN.compareAndSet(false, true)) {
                System.err.println("SafeX: signal sensitive_media_warning");
            }
        }
        if (sensitive) {
            FRAMES.get()[d - 1] = true;
        }
    }

    /**
     * Called when X parses {@code media_visibility_results} (age / adult gate).
     * Presence of that object means X decided to restrict the post's media, so
     * the whole entry is marked exactly like a sensitive-media flag.
     */
    public static void observeVisibility() {
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d > 0) {
            if (SIGNAL_VIS.compareAndSet(false, true)) {
                System.err.println("SafeX: signal media_visibility_results");
            }
            FRAMES.get()[d - 1] = true;
        }
    }

    /**
     * Terms appended to every search query as exclusions (" -term"). X applies
     * them server-side, so matching posts never reach the device. The user's own
     * query terms are never excluded: X treats "X -X" as invalid and blocks the
     * request entirely (verified on device).
     */
    private static final String[] EXCLUDE_TERMS = {
            "nsfw", "nsfwtwt", "porn", "pornhub", "hentai", "rule34", "onlyfans", "xxx",
            "pussy", "dick", "cock", "tits", "boobs", "anal", "cum", "nude", "naked",
            "sexting", "milf", "bdsm", "fetish", "teen", "teenager", "loli", "shota",
            "escort", "blowjob", "handjob", "creampie", "orgy", "gangbang", "threesome",
            "minichat", "anxiouspandaa",
            // Arabic
            "سكس", "نيك", "طيز", "شرموط", "عاري", "بورن",
            // Japanese
            "エロ", "おっぱい", "セックス", "中出し", "ヌード", "変態",
            // Russian / Spanish / Portuguese / German / French
            "секс", "порно", "голая", "sexo", "tetas", "putaria", "novinha",
            "nackt", "titten", "sexe", "salope",
            // Korean / Chinese / Turkish / Italian / Indonesian
            "야동", "섹스", "色情", "裸照", "seks", "sesso", "bokep", "telanjang"
    };

    private static final AtomicBoolean QUERY_LOGGED = new AtomicBoolean(false);
    private static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    private static final AtomicInteger PARAM_NAMES_LOGGED = new AtomicInteger();

    private static final AtomicBoolean HARD_BLOCKED = new AtomicBoolean(false);

    /** "minichatt" -> "minichat", "teenageerr" -> "tenager". */
    private static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char previous = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == previous) {
                continue;
            }
            out.append(c);
            previous = c;
        }
        return out.toString();
    }

    /**
     * True when the query itself is an explicit term. Such a search cannot be
     * cleaned with exclusions ("X -X" is rejected by X), so it is blocked
     * outright instead of returning a feed of explicit posts.
     */
    private static boolean isExplicitQuery(String query) {
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
                continue;
            }
            if (word.length() > 1) {
                String collapsed = collapse(word.toString());
                for (String term : EXCLUDE_TERMS) {
                    String stem = collapse(term.toLowerCase(java.util.Locale.ROOT));
                    if (collapsed.equals(stem)) {
                        return true;
                    }
                    if (stem.length() >= 5 && collapsed.contains(stem)) {
                        return true;
                    }
                }
            }
            word.setLength(0);
        }
        return false;
    }

    /** Spelling variants, so exact-term server exclusions still bite. */
    private static void addVariants(java.util.LinkedHashSet<String> out, String term) {
        String lower = term.toLowerCase(java.util.Locale.ROOT);
        out.add(term);
        if (lower.length() >= 4) {
            out.add(lower + lower.charAt(lower.length() - 1));
            out.add(lower + "s");
            if (lower.contains("ou")) {
                out.add(lower.replaceFirst("ou", "o"));
            }
            if (lower.contains("oo")) {
                out.add(lower.replaceFirst("oo", "o"));
            }
            if (lower.contains("ee")) {
                out.add(lower.replaceFirst("ee", "e"));
            }
            out.add(lower.replaceFirst("a", "aa"));
            out.add(lower.replaceFirst("e", "ee"));
            out.add(lower.replaceFirst("a", ""));
            out.add(lower.replaceFirst("e", ""));
        }
    }

    /**
     * Rewrites the outgoing search query: explicit searches are blocked, all
     * others get the blocklist (plus spelling variants) appended as exclusions
     * so X filters them server-side on every search surface.
     */
    public static String augmentQueryParam(String name, String value) {
        if (PARAM_NAMES_LOGGED.getAndIncrement() < 4) {
            System.err.println("SafeX: param setter name=" + name);
        }
        // X uses "q" on the legacy search endpoint and "query" on GraphQL.
        if (!("q".equals(name) || "query".equals(name)) || value == null || value.isEmpty()) {
            return value;
        }

        if (isExplicitQuery(value)) {
            if (HARD_BLOCKED.compareAndSet(false, true)) {
                System.err.println("SafeX: explicit search blocked");
            }
            return "safexnoresults9f3c";
        }

        java.util.Locale locale = java.util.Locale.ROOT;
        String lower = value.toLowerCase(locale);
        StringBuilder out = new StringBuilder(value);
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (String term : EXCLUDE_TERMS) {
            addVariants(terms, term);
        }

        int added = 0;
        for (String term : terms) {
            if (added >= 40 || out.length() > 1100) {
                break;
            }
            String lowerTerm = term.toLowerCase(locale);
            // Never exclude a term the user searched for: "X -X" makes X reject
            // the whole request.
            if (lower.contains(lowerTerm) || lower.contains(" -" + lowerTerm)) {
                continue;
            }
            out.append(" -").append(term);
            added++;
        }

        if (added > 0) {
            QUERY_COUNT.incrementAndGet();
            if (QUERY_LOGGED.compareAndSet(false, true)) {
                System.err.println("SafeX: search exclusions applied (added=" + added + ")");
            }
        }
        return out.toString();
    }

    /** Object-typed entry point for the generic map builder (e0.B). */
    public static Object augmentQueryParamObject(Object name, Object value) {
        if (!(name instanceof String) || !(value instanceof String)) {
            return value;
        }
        return augmentQueryParam((String) name, (String) value);
    }

    /** Diagnostics helper for logging; carries no post content. */
    public static String describe() {
        return "SafeX depth=" + DEPTH.get()[0];
    }
}
