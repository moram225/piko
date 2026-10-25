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

import java.util.concurrent.atomic.AtomicInteger;

public final class SafeX {

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
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            // Media parsed outside an entry (e.g. a detail request); ignore so
            // the marker cannot leak into the next timeline entry.
            return;
        }
        JsonMediaEntity entity = (JsonMediaEntity) media;
        boolean sensitive = entity.q;
        if (!sensitive) {
            JsonSensitiveMediaWarning warning = entity.u;
            sensitive = warning != null && (warning.a || warning.b || warning.c);
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
            FRAMES.get()[d - 1] = true;
        }
    }

    /** Diagnostics helper for logging; carries no post content. */
    public static String describe() {
        return "SafeX depth=" + DEPTH.get()[0];
    }
}
