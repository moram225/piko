package app.morphe.extension.twitter.safex.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PostFeatures {
    private final LinkedHashSet<FeatureKey> features = new LinkedHashSet<>();

    public void add(FeatureType type, String value) {
        if (value == null || value.isBlank()) return;
        features.add(new FeatureKey(type, value));
    }

    public Set<FeatureKey> all() {
        return Collections.unmodifiableSet(features);
    }

    public Set<String> values(FeatureType type) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FeatureKey k : features) if (k.type == type) out.add(k.value);
        return Collections.unmodifiableSet(out);
    }
}
