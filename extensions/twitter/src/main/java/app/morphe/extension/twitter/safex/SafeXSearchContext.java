package app.morphe.extension.twitter.safex;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Associates X's generic URT repository instance with the raw query used by
 * Search. A WeakHashMap avoids keeping destroyed search repositories alive.
 *
 * The cached-list collector owns the same repository in its private field `b`;
 * queryForOwner() resolves that indirection reflectively so the bytecode hook
 * does not need an extra scratch register.
 */
@SuppressWarnings("unused")
public final class SafeXSearchContext {
    private static final Map<Object, String> QUERY_BY_REPOSITORY =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    public static void register(Object repository, String rawQuery) {
        if (repository == null) return;
        QUERY_BY_REPOSITORY.put(repository, rawQuery == null ? "" : rawQuery);
    }

    public static String queryForOwner(Object owner) {
        if (owner == null) return "";

        String direct = QUERY_BY_REPOSITORY.get(owner);
        if (direct != null) return direct;

        // X 12.7.1: com.x.repositories.urt.e$b$a.b -> com.x.repositories.urt.g
        try {
            Field field = owner.getClass().getDeclaredField("b");
            field.setAccessible(true);
            Object repository = field.get(owner);
            String query = QUERY_BY_REPOSITORY.get(repository);
            return query == null ? "" : query;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private SafeXSearchContext() {}
}
