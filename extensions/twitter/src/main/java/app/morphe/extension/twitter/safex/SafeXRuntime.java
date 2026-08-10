package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.safex.core.Decision;
import app.morphe.extension.twitter.safex.core.FeatureExtractor;
import app.morphe.extension.twitter.safex.core.PostFeatures;
import app.morphe.extension.twitter.safex.core.SafeXLearner;
import app.morphe.extension.shared.Utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings({"unused", "rawtypes"})
public final class SafeXRuntime {
    private static volatile boolean enabled;
    private static volatile AndroidFeatureRepository repository;
    private static volatile SafeXLearner learner;
    private static final FeatureExtractor extractor = new FeatureExtractor();

    private static final ThreadLocal<Deque<EntryFrame>> frames =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final class ObservedTweet {
        final long id;
        final PostFeatures features;
        boolean xSensitive;

        ObservedTweet(long id, PostFeatures features) {
            this.id = id;
            this.features = features;
        }
    }

    private static final class EntryFrame {
        final List<ObservedTweet> tweets = new ArrayList<>();
        boolean hasSensitiveWarning;
        boolean pendingSensitiveAssociation;
    }

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

    public static void beginEntry() {
        if (!enabled) return;
        ensureInit();
        frames.get().push(new EntryFrame());
    }

    public static void observeSensitiveWarning(Object warning) {
        if (!enabled || warning == null) return;
        Deque<EntryFrame> stack = frames.get();
        if (stack.isEmpty()) return;

        try {
            Class<?> cls = warning.getClass();
            boolean a = cls.getField("a").getBoolean(warning);
            boolean b = cls.getField("b").getBoolean(warning);
            boolean c = cls.getField("c").getBoolean(warning);
            if (a || b || c) {
                EntryFrame frame = stack.peek();
                frame.hasSensitiveWarning = true;
                frame.pendingSensitiveAssociation = true;
            }
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    public static void observeTweet(Object tweetObj) {
        if (!enabled || tweetObj == null) return;
        Deque<EntryFrame> stack = frames.get();
        if (stack.isEmpty()) return;

        try {
            Tweet tweet = new Tweet(tweetObj);
            long tweetId = tweet.getTweetId();

            String text = tweet.getLongText();
            if (text == null) text = tweet.getShortText();
            if (text == null) text = "";

            EntryFrame frame = stack.peek();
            ObservedTweet observed = new ObservedTweet(tweetId, extractor.extract(text));
            if (frame.pendingSensitiveAssociation) {
                observed.xSensitive = true;
                frame.pendingSensitiveAssociation = false;
            }

            for (ObservedTweet old : frame.tweets) {
                if (old.id == tweetId) {
                    old.xSensitive |= observed.xSensitive;
                    return;
                }
            }
            frame.tweets.add(observed);
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    public static Object finishEntry(Object entry) {
        if (!enabled) return entry;

        Deque<EntryFrame> stack = frames.get();
        if (stack.isEmpty()) return entry;

        EntryFrame frame = stack.pop();
        try {
            ensureInit();

            if (frame.hasSensitiveWarning && frame.pendingSensitiveAssociation && !frame.tweets.isEmpty()) {
                frame.tweets.get(frame.tweets.size() - 1).xSensitive = true;
                frame.pendingSensitiveAssociation = false;
            }

            boolean drop = frame.hasSensitiveWarning;
            Set<Long> positiveIds = new HashSet<>();

            for (ObservedTweet tweet : frame.tweets) {
                if (tweet.xSensitive) {
                    if (repository.markObserved(tweet.id, "X_SENSITIVE")) {
                        learner.observePositive(tweet.features, SafeXLearner.WEIGHT_X_SENSITIVE);
                    }
                    repository.blockTweet(tweet.id, "X_SENSITIVE");
                    positiveIds.add(tweet.id);
                    drop = true;
                }
            }

            for (ObservedTweet tweet : frame.tweets) {
                if (repository.isTweetBlocked(tweet.id)) drop = true;
            }

            if (!drop) {
                for (ObservedTweet tweet : frame.tweets) {
                    Decision decision = learner.score(tweet.features);
                    if (decision.block) {
                        drop = true;
                        break;
                    }
                }
            }

            if (!drop) {
                for (ObservedTweet tweet : frame.tweets) {
                    if (!positiveIds.contains(tweet.id)
                            && repository.markObserved(tweet.id, "X_NOT_SENSITIVE")) {
                        learner.observeWeakSafe(tweet.features);
                    }
                }
            }

            return drop ? null : entry;
        } catch (Exception ex) {
            PikoUtils.logger(ex);
            return frame.hasSensitiveWarning ? null : entry;
        } finally {
            if (stack.isEmpty()) frames.remove();
        }
    }

    public static boolean isManualAction(Object buttonPressed) {
        return enabled
                && buttonPressed != null
                && "MarkTweetPossiblySensitive".equals(String.valueOf(buttonPressed));
    }

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
        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }
    }

    private SafeXRuntime() {}
}
