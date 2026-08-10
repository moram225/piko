package app.morphe.extension.twitter.safex.core;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FeatureExtractor {
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern HASHTAG = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]{2,80})");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+");
    private static final Pattern TOKEN = Pattern.compile("(?iu)[\\p{L}\\p{N}]{2,40}");

    public PostFeatures extract(String rawText) {
        String text = normalize(rawText == null ? "" : rawText);
        PostFeatures out = new PostFeatures();

        Matcher hashtagMatcher = HASHTAG.matcher(text);
        while (hashtagMatcher.find()) {
            String tag = normalizeHashtag(hashtagMatcher.group(1));
            out.add(FeatureType.HASHTAG, tag);
        }

        Matcher urlMatcher = URL.matcher(text);
        while (urlMatcher.find()) {
            String domain = domainOf(urlMatcher.group());
            if (domain != null) out.add(FeatureType.DOMAIN, domain);
        }

        String noUrls = URL.matcher(text).replaceAll(" ");
        Matcher tokenMatcher = TOKEN.matcher(noUrls);
        List<String> words = new ArrayList<>();
        while (tokenMatcher.find()) {
            String word = canonicalToken(tokenMatcher.group());
            if (!word.isBlank()) {
                words.add(word);
                out.add(FeatureType.WORD, word);
            }
        }

        for (int i = 0; i + 1 < words.size(); i++) {
            out.add(FeatureType.BIGRAM, words.get(i) + " " + words.get(i + 1));
        }
        for (int i = 0; i + 2 < words.size(); i++) {
            out.add(FeatureType.TRIGRAM, words.get(i) + " " + words.get(i + 1) + " " + words.get(i + 2));
        }
        return out;
    }

    public static String normalize(String input) {
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC);
        value = ZERO_WIDTH.matcher(value).replaceAll("");
        return value.toLowerCase(Locale.ROOT).trim();
    }

    public static String normalizeHashtag(String input) {
        String s = normalize(input).replaceAll("[._\\-]+", "");
        return canonicalToken(s);
    }

    public static String aggressiveHashtag(String input) {
        String s = normalizeHashtag(input)
                .replace('0', 'o')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't');
        return s.replaceAll("(.)\\1+", "$1");
    }

    private static String canonicalToken(String input) {
        return normalize(input);
    }

    private static String domainOf(String rawUrl) {
        try {
            String host = new URI(rawUrl).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception ignored) {
            return null;
        }
    }
}
