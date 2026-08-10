package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SafeX v0.6 list-level filter for X 12.7.1.
 *
 * Removes UrtTimelinePost objects before downstream flow/Compose sees them.
 * Both fresh and cached paths receive their owning URT repository so Search
 * query context can be resolved without contaminating the learner.
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class SafeXListFilter {
    public static final String BUILD_MARKER = "SafeX-v0.6-search-safety";

    private static final String DIAGNOSTIC_FILE = "SafeX-Diagnostics.txt";
    private static final String DIAGNOSTIC_FOLDER = "SafeX";
    private static final int MAX_DIAGNOSTIC_LINES = 1600;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MISSING_METHODS = new ConcurrentHashMap<>();
    private static final AtomicInteger diagnosticLines = new AtomicInteger();
    private static final AtomicInteger networkCalls = new AtomicInteger();
    private static final AtomicInteger cacheCalls = new AtomicInteger();
    private static volatile boolean diagnosticsInitialized;

    private static final class Stats {
        int inputItems;
        int outputItems;
        int postsSeen;
        int postsRemoved;
        int modulesSeen;
        int modulesChanged;
        int moduleChildrenRemoved;
        int errors;
        boolean searchContext;
        boolean queryRisk;
    }

    public static List filterNetworkItems(Object owner, List items) {
        String query = SafeXSearchContext.queryForOwner(owner);
        return filterItems(items, "network", networkCalls.incrementAndGet(), query);
    }

    public static List filterCachedItems(Object owner, List items) {
        String query = SafeXSearchContext.queryForOwner(owner);
        return filterItems(items, "cache", cacheCalls.incrementAndGet(), query);
    }

    // Compatibility with intermediate development builds. v0.6 bytecode uses
    // the two-argument overloads above.
    public static List filterNetworkItems(List items) {
        return filterItems(items, "network-legacy", networkCalls.incrementAndGet(), "");
    }

    public static List filterCachedItems(List items) {
        return filterItems(items, "cache-legacy", cacheCalls.incrementAndGet(), "");
    }

    private static List filterItems(List items, String source, int callNumber, String searchQuery) {
        SafeXRuntime.enable();
        Stats stats = new Stats();
        stats.searchContext = searchQuery != null && !searchQuery.trim().isEmpty();
        stats.queryRisk = stats.searchContext && SafeXRuntime.isHighRiskSearchQuery(searchQuery);

        if (items == null || items.isEmpty()) {
            diagnosticList(source, callNumber, stats);
            return items;
        }

        stats.inputItems = items.size();

        try {
            ArrayList<Object> output = new ArrayList<>(items.size());
            boolean changed = false;

            for (Object item : items) {
                Object filtered;
                try {
                    filtered = filterTimelineItem(item, stats, 0, searchQuery);
                } catch (Throwable t) {
                    stats.errors++;
                    PikoUtils.logger(t);
                    filtered = item;
                }

                if (filtered == null) {
                    changed = true;
                    continue;
                }
                if (filtered != item) changed = true;
                output.add(filtered);
            }

            stats.outputItems = output.size();
            diagnosticList(source, callNumber, stats);
            return changed ? output : items;
        } catch (Throwable t) {
            stats.errors++;
            PikoUtils.logger(t);
            diagnostic("source=" + source + " fatal-list-error=" + t.getClass().getName());
            return items;
        }
    }

    private static Object filterTimelineItem(Object item, Stats stats, int depth, String searchQuery) {
        if (item == null || depth > 5) return item;

        String name = item.getClass().getName();
        if (name.endsWith("UrtTimelinePost")) {
            stats.postsSeen++;
            if (SafeXModern.shouldRemoveTimelineItem(item, searchQuery)) {
                stats.postsRemoved++;
                return null;
            }
            return item;
        }

        if (name.endsWith("UrtTimelineModule")) {
            stats.modulesSeen++;
            return filterModule(item, stats, depth + 1, searchQuery);
        }

        return item;
    }

    /**
     * UrtTimelineModule.innerContent is a List<UrtTimelineModuleItem> in X
     * 12.7.1. Rebuild only modules whose children changed.
     */
    private static Object filterModule(Object module, Stats stats, int depth, String searchQuery) {
        Object rawInner = callNoArg(module, "getInnerContent");
        if (!(rawInner instanceof List)) return module;

        List inner = (List) rawInner;
        if (inner.isEmpty()) return module;

        ArrayList<Object> filteredChildren = new ArrayList<>(inner.size());
        boolean changed = false;

        for (Object wrapper : inner) {
            if (wrapper == null) {
                filteredChildren.add(null);
                continue;
            }

            Object child = callNoArg(wrapper, "getItem");
            if (child == null) {
                filteredChildren.add(wrapper);
                continue;
            }

            Object filteredChild;
            try {
                filteredChild = filterTimelineItem(child, stats, depth + 1, searchQuery);
            } catch (Throwable t) {
                stats.errors++;
                PikoUtils.logger(t);
                filteredChildren.add(wrapper);
                continue;
            }

            if (filteredChild == null) {
                changed = true;
                stats.moduleChildrenRemoved++;
                continue;
            }

            if (filteredChild == child) {
                filteredChildren.add(wrapper);
                continue;
            }

            Object dispensable = callNoArg(wrapper, "isDispensable");
            Object copiedWrapper = callByArity(wrapper, "copy", 2, filteredChild, dispensable);
            if (copiedWrapper != null) {
                changed = true;
                filteredChildren.add(copiedWrapper);
            } else {
                changed = true;
                stats.moduleChildrenRemoved++;
                diagnostic("module-wrapper-copy-failed class=" + wrapper.getClass().getName());
            }
        }

        if (!changed) return module;
        stats.modulesChanged++;

        if (filteredChildren.isEmpty()) return null;

        Object replacement = callByArity(
                module,
                "copy",
                7,
                filteredChildren,
                callNoArg(module, "getModuleHeader"),
                callNoArg(module, "getModuleFooter"),
                callNoArg(module, "getDisplayType"),
                callNoArg(module, "getSortIndex"),
                callNoArg(module, "getEntryId"),
                callNoArg(module, "getClientEventInfo")
        );

        if (replacement != null) return replacement;

        diagnostic("module-copy-failed class=" + module.getClass().getName()
                + " childrenBefore=" + inner.size()
                + " childrenAfter=" + filteredChildren.size());
        return null;
    }

    private static Object callNoArg(Object target, String name) {
        return callByArity(target, name, 0);
    }

    private static Object callByArity(Object target, String name, int arity, Object... args) {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name + "/" + arity;
        if (MISSING_METHODS.containsKey(key)) return null;

        try {
            Method method = METHOD_CACHE.get(key);
            if (method == null) {
                method = findMethod(target.getClass(), name, arity);
                if (method == null) {
                    MISSING_METHODS.put(key, Boolean.TRUE);
                    diagnostic("missing-method " + key);
                    return null;
                }
                method.setAccessible(true);
                METHOD_CACHE.put(key, method);
            }
            return method.invoke(target, args);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            diagnostic("invoke-error " + key + " " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, int arity) {
        for (Method method : cls.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == arity) {
                return method;
            }
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == arity) {
                return method;
            }
        }
        return null;
    }

    private static void diagnosticList(String source, int callNumber, Stats stats) {
        if (stats == null) return;
        boolean important = callNumber <= 8
                || stats.postsRemoved > 0
                || stats.moduleChildrenRemoved > 0
                || stats.errors > 0
                || stats.searchContext
                || callNumber % 100 == 0;
        if (!important) return;

        diagnostic("hook=" + source
                + " call=" + callNumber
                + " in=" + stats.inputItems
                + " out=" + stats.outputItems
                + " posts=" + stats.postsSeen
                + " removed=" + stats.postsRemoved
                + " modules=" + stats.modulesSeen
                + " modulesChanged=" + stats.modulesChanged
                + " moduleChildrenRemoved=" + stats.moduleChildrenRemoved
                + " searchContext=" + stats.searchContext
                + " queryRisk=" + stats.queryRisk
                + " errors=" + stats.errors);
    }

    private static synchronized void diagnostic(String line) {
        try {
            if (diagnosticLines.getAndIncrement() >= MAX_DIAGNOSTIC_LINES) return;
            if (!diagnosticsInitialized) {
                PikoUtils.pikoWriteFile(
                        DIAGNOSTIC_FILE,
                        DIAGNOSTIC_FOLDER,
                        BUILD_MARKER + "\n",
                        false
                );
                diagnosticsInitialized = true;
            }
            PikoUtils.pikoWriteFile(
                    DIAGNOSTIC_FILE,
                    DIAGNOSTIC_FOLDER,
                    line + "\n",
                    true
            );
        } catch (Throwable ignored) {
        }
    }

    private SafeXListFilter() {}
}
