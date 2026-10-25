/*
 * SafeX settings lock.
 *
 * X stores "hide sensitive content" as three account-level levels
 * (view_adult_content / view_violent_content / view_other_content), each
 * Warn, Allow or Drop, behind com.twitter.sensitivemedia.i (impl:
 * com.twitter.sensitivemedia.request.c).
 *
 * Note: the level enum's real dex name is com.twitter.sensitivemedia.model.a
 * (jadx displays it as SensitiveMediaLevel from Kotlin metadata), so the stub
 * used here is `model.a`.
 *
 * - forceDrop(): every write to that setting is replaced with Drop x3, so the
 *   UI cannot turn hiding off.
 * - enforce(): when the app reads the setting, push Drop x3 once per process so
 *   the account is moved to "hide" without user interaction.
 */

package app.morphe.extension.twitter.patches;

import com.twitter.sensitivemedia.i;
import com.twitter.sensitivemedia.model.a;
import com.twitter.sensitivemedia.model.b;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SafeXSettings {

    private static final AtomicBoolean PUSHED = new AtomicBoolean(false);
    private static final AtomicBoolean SEARCH_READ_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SEARCH_WRITE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DROP_LOGGED = new AtomicBoolean(false);

    private SafeXSettings() {
    }

    /** The model X uses for "hide sensitive content" in every category. */
    public static b dropModel() {
        return new b(a.Drop, a.Drop, a.Drop);
    }

    /** Replaces any attempted write with Drop x3. */
    public static b forceDrop(b ignored) {
        if (!DROP_LOGGED.getAndSet(true)) {
            System.err.println("SafeXSettings: write to account forced to Drop x3");
        }
        return dropModel();
    }

    /**
     * Forces Search settings to "Hide sensitive content" (and the blocked/muted
     * filter). Takes and returns Object so it can be injected into both the
     * typed method and its synthetic bridge without a VerifyError.
     */
    public static Object forceSearchModel(Object ignored) {
        return new com.twitter.model.search.c(new com.twitter.model.search.c.a());
    }

    /** Read path of the search settings (what the UI shows). */
    public static Object forceSearchRead(Object ignored) {
        if (!SEARCH_READ_LOGGED.getAndSet(true)) {
            System.err.println("SafeXSettings: search settings read -> forced on");
        }
        return forceSearchModel(ignored);
    }

    /** Write path of the search settings (what is sent to X). */
    public static Object forceSearchWrite(Object ignored) {
        if (!SEARCH_WRITE_LOGGED.getAndSet(true)) {
            System.err.println("SafeXSettings: search settings write -> forced on");
        }
        return forceSearchModel(ignored);
    }

    /** Logs X's answer to the search settings update (true = server accepted). */
    public static Object reportSearchWrite(Object result) {
        System.err.println("SafeXSettings: search settings update result=" + result);
        return result;
    }

    /**
     * Called when X reads the setting. Pushes Drop x3 once per process so the
     * account is actually switched to hiding, not just displayed as such.
     */
    public static void enforce(Object repository) {
        if (!(repository instanceof i)) {
            return;
        }
        if (!PUSHED.compareAndSet(false, true)) {
            return;
        }
        try {
            System.err.println("SafeXSettings: pushing Drop x3 to account");
            ((i) repository).b(dropModel()).a(new AccountObserver());
        } catch (Throwable t) {
            System.err.println("SafeXSettings: push failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Reports whether X accepted the account update, so we can verify it landed. */
    private static final class AccountObserver implements io.reactivex.w<Object> {
        @Override
        public void onSubscribe(io.reactivex.disposables.c disposable) {
        }

        @Override
        public void onSuccess(Object value) {
            System.err.println("SafeXSettings: account update accepted");
        }

        @Override
        public void onError(Throwable error) {
            System.err.println("SafeXSettings: account update error: " + error);
        }
    }
}
