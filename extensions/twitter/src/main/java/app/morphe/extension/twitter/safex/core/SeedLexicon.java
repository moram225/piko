package app.morphe.extension.twitter.safex.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SeedLexicon {
    private final Set<String> hardWords = new LinkedHashSet<>();
    private final Map<String, Double> weightedWords = new LinkedHashMap<>();
    private final Map<String, Double> weightedPhrases = new LinkedHashMap<>();
    private final Map<String, Double> hashtagFamilies = new LinkedHashMap<>();
    private final Map<String, Double> domains = new LinkedHashMap<>();

    public SeedLexicon() {
        Collections.addAll(hardWords,
                "nsfw", "porn", "porno", "pornography", "xxx",
                "onlyfans", "fansly", "hentai", "rule34",
                "blowjob", "handjob", "deepthroat", "creampie",
                "bukkake", "gangbang", "cumshot", "camgirl",
                "sexcam", "sextape", "analporn");

        putWords(4.2,
                "nudes", "nudity", "naked", "explicit", "hardcore",
                "masturbation", "masturbating", "fingering", "penetration");
        putWords(3.0, "erotic", "erotica", "softcore", "uncensored");
        putWords(1.2, "sex", "sexual", "sexy", "lingerie");
        putWords(0.8, "adult", "leak", "leaked", "private", "exclusive");

        putPhrases(4.8,
                "onlyfans leak", "onlyfans leaked", "fansly leak",
                "leaked nudes", "free nudes", "sex tape", "sex video",
                "nude video", "nude pics", "nude photos",
                "uncensored video", "uncensored clip");
        putPhrases(2.2,
                "full video", "watch full", "watch here",
                "link in bio", "exclusive content", "private video",
                "dm for nudes", "dm for content");

        hashtagFamilies.put("nsfw", 8.0);
        hashtagFamilies.put("porn", 8.0);
        hashtagFamilies.put("xxx", 7.5);
        hashtagFamilies.put("onlyfans", 7.0);
        hashtagFamilies.put("fansly", 7.0);
        hashtagFamilies.put("hentai", 7.0);
        hashtagFamilies.put("rule34", 7.0);
        hashtagFamilies.put("chudai", 7.0);
        hashtagFamilies.put("momson", 6.0);

        // Legitimate uses exist, so these remain weak/contextual.
        hashtagFamilies.put("teen", 0.8);
        hashtagFamilies.put("teenage", 0.8);
        hashtagFamilies.put("teenager", 0.8);

        domains.put("onlyfans.com", 8.0);
        domains.put("fansly.com", 8.0);
        domains.put("pornhub.com", 8.0);
        domains.put("xvideos.com", 8.0);
        domains.put("xnxx.com", 8.0);
    }

    private void putWords(double weight, String... values) {
        for (String value : values) weightedWords.put(value, weight);
    }

    private void putPhrases(double weight, String... values) {
        for (String value : values) weightedPhrases.put(value, weight);
    }

    public boolean hardWord(String word) { return hardWords.contains(word); }
    public double wordWeight(String word) { return weightedWords.getOrDefault(word, 0.0); }
    public double phraseWeight(String phrase) { return weightedPhrases.getOrDefault(phrase, 0.0); }
    public double domainWeight(String domain) { return domains.getOrDefault(domain, 0.0); }
    public Map<String, Double> hashtagFamilies() { return Collections.unmodifiableMap(hashtagFamilies); }
}
