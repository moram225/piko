package app.morphe.extension.twitter.safex.core;

import java.util.Objects;

public final class FeatureKey {
    public final FeatureType type;
    public final String value;

    public FeatureKey(FeatureType type, String value) {
        this.type = Objects.requireNonNull(type);
        this.value = Objects.requireNonNull(value);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FeatureKey)) return false;
        FeatureKey that = (FeatureKey) other;
        return type == that.type && value.equals(that.value);
    }

    @Override public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override public String toString() {
        return type + ":" + value;
    }
}
