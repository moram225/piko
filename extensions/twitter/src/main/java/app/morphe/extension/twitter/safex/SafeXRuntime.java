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
 * v0.3 intentionally does not mutate X's sensitive-media models. Automatic
 * filtering is driven by the raw GraphQL response, where X 12.7.1 exposes the
 * canonical post's possibly_sensitive flag and text before UI rendering.
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
     * Evaluates one post read from X's server response.
     *
     * Positive training sources:
     * - X possibly_sensitive=true: +1.0
     * - manual Mark as NSFW: +2.0 (handled separately)
     *
     * Auto predictions never train themselves.
     */
    public static boolean classifyNetworkPost(long postId, String classifierText, boolean xSensitive) {
        if (!enabled || postId == 0L) return false;

        try {
            ensureInit();
            PostFeatures features = extractor.extract(classifierText == null ? "" : classifierText);

            if (xSensitive) {
                if (repository.markObserved(postId, "X_SENSITIVE")) {
                    learner.observePositive(features, SafeXLearner.WEIGHT_X_SENSITIVE);
                }
                repository.blockTweet(postId, "X_SENSITIVE");
                return true;
            }

            // A manual block must remain effective when X serves the same post
            // from another timeline, cache, search result, or quote.
            if (repository.isTweetBlocked(postId)) return true;

            Decision decision = learner.score(features);
            // IMPORTANT: do not write AUTO_NSFW as positive evidence.
            return decision.block;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    /**
     * Weak-negative evidence is committed only after the containing timeline
     * entry survives the response filter.
     */
    public static void observeNetworkSafe(long postId, String classifierText) {
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

    public static boolean isManualAction(Object buttonPressed) {
        return enabled
                && buttonPressed != null
                && "MarkTweetPossiblySensitive".equals(String.valueOf(buttonPressed));
    }

    /**
     * Called from the existing X three-dot post menu hook.
     */
    public static void markManualNsfw(Object tweetObj) {
        if (!enabled || tweetObj == null) return;

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
