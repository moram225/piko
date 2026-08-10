package app.morphe.extension.twitter.safex.core;

import java.util.HashSet;
import java.util.Set;

public final class StringSimilarity {
    private StringSimilarity() {}

    public static double hashtagSimilarity(String a, String b) {
        String ca = FeatureExtractor.normalizeHashtag(a);
        String cb = FeatureExtractor.normalizeHashtag(b);
        if (ca.equals(cb)) return 1.0;

        String aa = FeatureExtractor.aggressiveHashtag(ca);
        String ab = FeatureExtractor.aggressiveHashtag(cb);
        if (aa.equals(ab) && aa.length() >= 4) return 0.98;

        double lev = normalizedLevenshtein(ca, cb);
        double grams = ngramJaccard(ca, cb, 3);
        return Math.max(lev, grams);
    }

    public static double normalizedLevenshtein(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        int dist = prev[b.length()];
        return 1.0 - ((double) dist / Math.max(a.length(), b.length()));
    }

    public static double ngramJaccard(String a, String b, int n) {
        Set<String> ga = grams(a, n);
        Set<String> gb = grams(b, n);
        if (ga.isEmpty() || gb.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(ga);
        intersection.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> grams(String s, int n) {
        Set<String> out = new HashSet<>();
        if (s.length() < n) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i <= s.length() - n; i++) out.add(s.substring(i, i + n));
        return out;
    }
}
