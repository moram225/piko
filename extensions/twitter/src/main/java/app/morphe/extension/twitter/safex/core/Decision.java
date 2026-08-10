package app.morphe.extension.twitter.safex.core;

import java.util.Collections;
import java.util.List;

public final class Decision {
    public final boolean block;
    public final double score;
    public final List<String> reasons;

    public Decision(boolean block, double score, List<String> reasons) {
        this.block = block;
        this.score = score;
        this.reasons = Collections.unmodifiableList(reasons);
    }

    @Override public String toString() {
        return "Decision{block=" + block + ", score=" + score + ", reasons=" + reasons + "}";
    }
}
