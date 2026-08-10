package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.safex.core.Decision;
import app.morphe.extension.twitter.safex.core.FeatureExtractor;
import app.morphe.extension.twitter.safex.core.PostFeatures;
import app.morphe.extension.twitter.safex.core.SafeXLearner;

/**
 * SafeX runtime state and learning policy.
 *
 * v0.4 deliberately keeps classification independent of X's transport layer.
 * Modern X can supply a post from network, Apollo/other caches, X's local DB,
 * search, a module, a quote, or a repost. The modern UI/model hook passes the
 * final post object here immediately before rendering.
 */
@SuppressWarnings("unused")
public final class SafeXRuntime {
    private static volatile boolean enabled;
    private static volatile AndroidFeatureRepository repository;
    private static volatile SafeXLearner learner;
    private static final FeatureExtractor extractor = new FeatureExtractor();

    public static void enable() {
        enabled = true;
        ensureInit();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void ensureInit() {
        if (!enabled || learner != null) return;
        synchronized (SafeXRuntime.class) {
            if (learner == null) {
                repository = new AndroidFeatureRepository(Utils.getContext());
                learner = new SafeXLearner(repository);
            }
        }
    }

    /**
     * Evaluate one concrete post object after X has produced its modern model.
     *
     * Rules:
     * - X's own sensitive signal is a strong positive example (+1.0).
     * - a previously manually/server-blocked post remains blocked everywhere.
     * - a learned prediction may block, but never trains itself.
     * - if an X-sensitive object has no usable ID, block it anyway but skip
     *   persistence/training for that object.
     */
    public static boolean classifyPost(long postId, String classifierText, boolean xSensitive) {
        if (!enabled) enable();

        try {
            ensureInit();
            String text = classifierText == null ? "" : classifierText;
            PostFeatures features = extractor.extract(text);

            if (xSensitive) {
                if (postId != 0L) {
                    if (repository.markObserved(postId, "X_SENSITIVE")) {
                        learner.observePositive(features, SafeXLearner.WEIGHT_X_SENSITIVE);
                    }
                    repository.blockTweet(postId, "X_SENSITIVE");
                }
                return true;
            }

            if (postId != 0L && repository.isTweetBlocked(postId)) return true;

            Decision decision = learner.score(features);
            // IMPORTANT: automatic SafeX predictions are NEVER positive labels.
            return decision.block;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            // Learned-classifier failures fail open. Native X sensitivity is
            // handled before this catch whenever we can read it successfully.
            return xSensitive;
        }
    }

    /**
     * Commit weak negative evidence only after the complete post, including any
     * quoted/reposted child, has survived SafeX classification.
     */
    public static void observeSafePost(long postId, String classifierText) {
        if (!enabled || postId == 0L) return;

        try {
            ensureInit();
            if (repository.markObserved(postId, "X_NOT_SENSITIVE")) {
                PostFeatures features = extractor.extract(classifierText == null ? "" : classifierText);
                learner.observeWeakSafe(features);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    // Compatibility wrappers for the retired v0.3 response-filter class. v0.4
    // does not wire that network hook, but keeping these avoids stale extension
    // linkage if a developer compares/builds intermediate commits.
    public static boolean classifyNetworkPost(long postId, String classifierText, boolean xSensitive) {
        return classifyPost(postId, classifierText, xSensitive);
    }

    public static void observeNetworkSafe(long postId, String classifierText) {
        observeSafePost(postId, classifierText);
    }

    public static boolean isManualAction(Object buttonPressed) {
        return enabled
                && buttonPressed != null
                && "MarkTweetPossiblySensitive".equals(String.valueOf(buttonPressed));
    }

    /** Called from X's existing three-dot post menu hook. */
    public static void markManualNsfw(Object tweetObj) {
        if (!enabled) enable();
        if (tweetObj == null) return;

        try {
            ensureInit();
            Tweet tweet = new Tweet(tweetObj);
            long tweetId = tweet.getTweetId();

            String text = tweet.getLongText();
            if (text == null) text = tweet.getShortText();
            if (text == null) text = "";

            PostFeatures features = extractor.extract(text);
            if (repository.markObserved(tweetId, "MANUAL_NSFW")) {
                learner.observePositive(features, SafeXLearner.WEIGHT_MANUAL_NSFW);
            }
            repository.blockTweet(tweetId, "MANUAL_NSFW");
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private SafeXRuntime() {}
}
