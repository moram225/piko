import app.morphe.extension.twitter.patches.SafeX;
import com.twitter.model.json.core.JsonMediaEntity;
import com.twitter.model.json.core.JsonSensitiveMediaWarning;

/**
 * Regression tests for the SafeX decision state machine.
 *
 * Compiles and runs the real shipped SafeX.java (not a copy) against the
 * compile-time stubs, on a plain JVM. No Android runtime required.
 */
public final class SafeXPolicyTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean expected, boolean actual) {
        if (expected == actual) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static JsonMediaEntity media(boolean possiblySensitive, Boolean adult, Boolean violence, Boolean other) {
        JsonMediaEntity entity = new JsonMediaEntity();
        entity.q = possiblySensitive;
        if (adult != null || violence != null || other != null) {
            JsonSensitiveMediaWarning warning = new JsonSensitiveMediaWarning();
            warning.a = Boolean.TRUE.equals(adult);
            warning.b = Boolean.TRUE.equals(violence);
            warning.c = Boolean.TRUE.equals(other);
            entity.u = warning;
        }
        return entity;
    }

    public static void main(String[] args) {
        // 1. possibly_sensitive == true drops the entry
        SafeX.beginEntry();
        SafeX.observeMedia(media(true, null, null, null));
        check("possibly_sensitive drops entry", true, SafeX.endEntry());

        // 2-4. each warning flag independently drops
        SafeX.beginEntry();
        SafeX.observeMedia(media(false, true, false, false));
        check("adult_content drops entry", true, SafeX.endEntry());

        SafeX.beginEntry();
        SafeX.observeMedia(media(false, false, true, false));
        check("graphic_violence drops entry", true, SafeX.endEntry());

        SafeX.beginEntry();
        SafeX.observeMedia(media(false, false, false, true));
        check("other drops entry", true, SafeX.endEntry());

        // 5. unclassified media is kept
        SafeX.beginEntry();
        SafeX.observeMedia(media(false, false, false, false));
        check("clean media keeps entry", false, SafeX.endEntry());

        // 6. media parsed outside any entry must not leak into the next one
        SafeX.observeMedia(media(true, null, null, null));
        SafeX.beginEntry();
        SafeX.observeMedia(media(false, false, false, false));
        check("out-of-entry media does not leak", false, SafeX.endEntry());

        // 7. nested frames: sensitive child drops, clean parent survives
        SafeX.beginEntry();
        SafeX.beginEntry();
        SafeX.observeMedia(media(true, null, null, null));
        check("sensitive child dropped", true, SafeX.endEntry());
        check("clean parent kept", false, SafeX.endEntry());

        // 8. no bleed between sequential entries
        SafeX.beginEntry();
        SafeX.observeMedia(media(true, null, null, null));
        SafeX.endEntry();
        SafeX.beginEntry();
        check("next entry unaffected", false, SafeX.endEntry());

        // 9. non-media objects are ignored
        SafeX.beginEntry();
        SafeX.observeMedia("not a media entity");
        SafeX.observeMedia(null);
        check("non-media ignored", false, SafeX.endEntry());

        // 10. media_visibility_results (age/adult gate) drops the entry
        SafeX.beginEntry();
        SafeX.observeVisibility();
        check("media_visibility_results drops entry", true, SafeX.endEntry());

        // 11. visibility outside an entry must not leak
        SafeX.observeVisibility();
        SafeX.beginEntry();
        check("out-of-entry visibility does not leak", false, SafeX.endEntry());

        // 12. balanced bracket is harmless
        check("unbalanced endEntry is safe", false, SafeX.endEntry());

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
