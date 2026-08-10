package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.safex.core.Decision;
import app.morphe.extension.twitter.safex.core.FeatureExtractor;
import app.morphe.extension.twitter.safex.core.PostFeatures;
import app.morphe.extension.twitter.safex.core.SafeXLearner;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SafeX runtime state and learning policy.
 *
 * v0.6 keeps automatic classification post-local, but adds a separate Search
 * context risk check. A risky query never becomes training evidence; it is only
 * used to stop visual-media search results that X itself labels as safe.
 */
@SuppressWarnings("unused")
public final class SafeXRuntime {
    private static final Pattern QUERY_TOKEN =
            Pattern.compile("(?iu)[\\p{L}\\p{N}_]{2,80}");

    /**
     * Terms are intentionally stricter in Search context than in ordinary tweet
     * text. For example, the word "sex" is not a global hard-block in tweet
     * text because educational/news uses exist; a search for "sex" is however
     * high-risk context for visual media when the user's goal is zero sexual
     * imagery. Weak ambiguous terms such as teen/adult/model remain excluded.
     */
    private static final Set<String> STRICT_SEARCH_TERMS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "nsfw", "porn", "porno", "pornography", "xxx",
                    "sex", "sexual", "sexvideo", "sexvideos", "sextape", "sextapes",
                    "nude", "nudes", "nudity", "naked", "explicit", "erotic", "erotica",
                    "hardcore", "softcore", "onlyfans", "fansly", "hentai", "rule34", "r34",
                    "blowjob", "handjob", "rimjob", "deepthroat", "creampie", "bukkake",
                    "gangbang", "threesome", "orgy", "masturbation", "masturbating",
                    "fingering", "penetration", "cumshot", "cumshots", "semen", "ejaculation",
                    "pussy", "vagina", "vaginal", "cock", "penis", "boobs", "tits", "titties",
                    "analporn", "sexcam", "camgirl", "camgirls"
            ))
    );

    private static final Set<String> QUERY_OPERATORS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "from", "to", "filter", "since", "until", "lang", "near", "within"
            ))
    );

    private static final ConcurrentHashMap<String, Boolean> QUERY_RISK_CACHE =
            new ConcurrentHashMap<>();

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
            // Automatic SafeX predictions are NEVER positive labels.
            return decision.block;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return xSensitive;
        }
    }

    /**
     * Search-only contextual risk. Never updates the learner.
     *
     * Besides strict explicit terms, each query token is evaluated as a
     * synthetic hashtag so learned/seeded mutation families such as momsonn and
     * chudaii work even when the user searches without a '#'. Weak families
     * like teen remain below threshold.
     */
    public static boolean isHighRiskSearchQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return false;
        if (!enabled) enable();

        String normalized = FeatureExtractor.normalize(rawQuery);
        Boolean cached = QUERY_RISK_CACHE.get(normalized);
        if (cached != null) return cached;

        boolean risk = false;
        try {
            ensureInit();

            Matcher matcher = QUERY_TOKEN.matcher(normalized);
            while (matcher.find()) {
                String token = matcher.group();
                if (STRICT_SEARCH_TERMS.contains(token)) {
                    risk = true;
                    break;
                }
            }

            if (!risk) {
                Decision raw = learner.score(extractor.extract(rawQuery));
                risk = raw.block;
            }

            if (!risk) {
                matcher = QUERY_TOKEN.matcher(normalized);
                while (matcher.find()) {
                    String token = matcher.group();
                    if (QUERY_OPERATORS.contains(token)) continue;
                    Decision asHashtag = learner.score(extractor.extract("#" + token));
                    if (asHashtag.block) {
                        risk = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
            risk = false;
        }

        // Searches are few; keep bounded so a long-running app cannot grow this
        // cache without limit. Risk is recomputed after learned state changes if
        // the small cache is rotated.
        if (QUERY_RISK_CACHE.size() > 256) QUERY_RISK_CACHE.clear();
        QUERY_RISK_CACHE.put(normalized, risk);
        return risk;
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
            // A manual label can change learned hashtag-family risk. Clear the
            // small Search-context cache so later searches see new evidence.
            QUERY_RISK_CACHE.clear();
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private SafeXRuntime() {}
}
