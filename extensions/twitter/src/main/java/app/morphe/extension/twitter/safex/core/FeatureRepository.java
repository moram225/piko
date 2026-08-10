package app.morphe.extension.twitter.safex.core;

import java.util.Map;

public interface FeatureRepository {
    FeatureStats get(FeatureKey key);
    void put(FeatureKey key, FeatureStats stats);
    Map<FeatureKey, FeatureStats> listByType(FeatureType type);
    long getDocumentCount();
    void setDocumentCount(long count);
}
