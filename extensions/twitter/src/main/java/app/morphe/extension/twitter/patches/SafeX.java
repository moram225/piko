/*
 * SafeX - remove posts that X itself classifies as sensitive, before render.
 *
 * Detection is X's own classification, not a local heuristic:
 *   JsonMediaEntity.q            == "possibly_sensitive"
 *   JsonMediaEntity.u            == "sensitive_media_warning" / "ext_sensitive_media_warning"
 *   JsonSensitiveMediaWarning.a  == "adult_content"
 *   JsonSensitiveMediaWarning.b  == "graphic_violence"
 *   JsonSensitiveMediaWarning.c  == "other"
 *
 * The timeline entry parser is wrapped by beginEntry()/endEntry(), and every
 * media entity parsed on that thread marks the current entry. checkEntry()
 * then returns null, which is the drop path piko already uses for ads: the
 * JSON mapper returns null and the entry never reaches the model or the UI.
 */

package app.morphe.extension.twitter.patches;

import com.twitter.model.json.core.JsonMediaEntity;
import com.twitter.model.json.core.JsonSensitiveMediaWarning;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SafeX {

    /** One-shot runtime diagnostics: proves which hooks X actually executes. */
    private static final AtomicBoolean ENTRY_SEEN = new AtomicBoolean(false);
    private static final AtomicBoolean MEDIA_SEEN = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_Q = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_WARN = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNAL_VIS = new AtomicBoolean(false);
    private static final AtomicInteger ENTRY_COUNT = new AtomicInteger();

    /**
     * True once a timeline entry parse has started. When SafeX is active it must
     * see X's original classification flags, so the "Show sensitive media"
     * patch must not clear them (see TimelineEntry.showSensitiveMedia).
     */
    public static volatile boolean active;

    /** Number of entries dropped since process start; diagnostics only. */
    private static final AtomicInteger DROPPED = new AtomicInteger();

    /** Reads and increments the drop counter (never logs post content). */
    public static int recordDrop() {
        return DROPPED.incrementAndGet();
    }

    /** Current drop count, for a settings/diagnostics surface. */
    public static int droppedCount() {
        return DROPPED.get();
    }

    /** Maximum entry nesting we track; deeper frames are ignored, never trusted. */
    private static final int MAX_DEPTH = 32;

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    private static final ThreadLocal<boolean[]> FRAMES = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[MAX_DEPTH];
        }
    };

    private SafeX() {
    }

    /** Called at the very start of a timeline entry / module item parse. */
    public static void beginEntry() {
        active = true;
        if (ENTRY_SEEN.compareAndSet(false, true)) {
            System.err.println("SafeX: entry parse hook active");
        }
        int entries = ENTRY_COUNT.incrementAndGet();
        if (entries % 25 == 0) {
            System.err.println("SafeX: entries parsed=" + entries);
        }
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d < MAX_DEPTH) {
            FRAMES.get()[d] = false;
            depth[0] = d + 1;
        }
    }

    /**
     * Called when an entry parse finishes. Returns true when anything parsed
     * inside that entry was classified sensitive, and pops the frame.
     */
    public static boolean endEntry() {
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            return false;
        }
        depth[0] = d - 1;
        return FRAMES.get()[d - 1];
    }

    /**
     * Called after each media entity is parsed. Marks the innermost entry
     * frame when X flagged the media, using the three classification flags.
     */
    public static void observeMedia(Object media) {
        if (!(media instanceof JsonMediaEntity)) {
            return;
        }
        if (MEDIA_SEEN.compareAndSet(false, true)) {
            System.err.println("SafeX: media parse hook active");
        }
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            // Media parsed outside an entry (e.g. a detail request); ignore so
            // the marker cannot leak into the next timeline entry.
            return;
        }
        JsonMediaEntity entity = (JsonMediaEntity) media;
        boolean sensitive = entity.q;
        if (sensitive && SIGNAL_Q.compareAndSet(false, true)) {
            System.err.println("SafeX: signal possibly_sensitive");
        }
        if (!sensitive) {
            JsonSensitiveMediaWarning warning = entity.u;
            sensitive = warning != null && (warning.a || warning.b || warning.c);
            if (sensitive && SIGNAL_WARN.compareAndSet(false, true)) {
                System.err.println("SafeX: signal sensitive_media_warning");
            }
        }
        if (sensitive) {
            FRAMES.get()[d - 1] = true;
        }
    }

    /**
     * Called when X parses {@code media_visibility_results} (age / adult gate).
     * Presence of that object means X decided to restrict the post's media, so
     * the whole entry is marked exactly like a sensitive-media flag.
     */
    public static void observeVisibility() {
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d > 0) {
            if (SIGNAL_VIS.compareAndSet(false, true)) {
                System.err.println("SafeX: signal media_visibility_results");
            }
            FRAMES.get()[d - 1] = true;
        }
    }

    /**
     * Terms appended to every search query as exclusions (" -term"). X applies
     * them server-side, so matching posts never reach the device. The user's own
     * query terms are never excluded: X treats "X -X" as invalid and blocks the
     * request entirely (verified on device).
     */
    private static final String[] EXCLUDE_TERMS = {
            // --- English core ---
            "nsfw", "nsfwtwt", "nsfwtw", "nsfwpost", "nsfwcontent", "nsfwedit",
            "porn", "porno", "pornhub", "pornstar", "pornvideo", "pornography",
            "xxx", "xxxvideo", "xvideo", "xvideos", "xhamster", "xnxx",
            "hentai", "hentaii", "rule34", "r34", "e621",
            "onlyfans", "onlyfan", "onlyfansleak", "fansly", "fanhouse",
            "chaturbate", "camgirl", "camwhore", "camsoda", "stripchat",
            "pussy", "pussycat", "dick", "cock", "tits", "titty", "boobs", "boob",
            "ass", "anal", "analsex", "cum", "cumshot", "cumslut",
            "horny", "nude", "nudes", "naked", "sex", "sexy", "sexvideo",
            "sexting", "sextape", "milf", "dilf", "gilf", "bdsm", "fetish",
            "fetische", "teen", "teens", "teenager", "teenporn", "loli", "shota",
            "escort", "escorts", "blowjob", "handjob", "deepthroat", "creampie",
            "orgy", "gangbang", "threesome", "squirt", "orgasm", "masturbation",
            "masturbate", "dildo", "vibrator", "bondage", "asshole", "anus",
            "hardcore", "softcore", "amateur", "webcam", "sexcam", "sexchat",
            "sextoy", "sextoys", "fuck", "fucking", "fucked", "doggystyle",
            "cowgirl", "titfuck", "roughsex", "dirty", "naughty", "kinky",
            "kink", "fetish", "horny", "squirting", "cumming", "orgasms",
            // --- leaks / platforms ---
            "leak", "leaks", "leaked", "leakednudes", "nudeleaks", "leaknudes",
            "snapchatleaks", "snapleaks", "snapexposed", "snapchatnudes",
            "telegramleaks", "telegramnudes", "telegramporn", "telegroups",
            "discordleaks", "discordnudes", "telebox", "teleboxxx",
            "arabtelebox", "arabmega", "megaleaks", "megalinks", "mega",
            "dropboxleaks", "driveleaks", "mega.nz", "linktree",
            "candid", "candids", "creepshots", "upskirt", "downblouse",
            "voyeur", "voyeurism", "hiddencam", "spycam", "peeping",
            "exposed", "exposing", "revengeporn", "sextortion", "nudeshare",
            "trading", "trades", "nudetrade", "cp", "childporn",
            // --- Arabic (script) ---
            "سكس", "سكسي", "جنس", "جنسي", "نيك", "طيز", "كس", "زب", "عاهر",
            "عاهرة", "شرموط", "شرموطة", "عاري", "عريان", "بورن", "لواط",
            "سحاق", "شهوة", "إغراء", "مثارة", "اباحي", "إباحي", "اباحية",
            "إباحية", "خلاعة", "خلاعي", "دعارة", "قحبة", "قحب", "فاحشة",
            "منحرف", "منحرفة", "مومس", "بغي", "زانية", "زاني", "خليع",
            "مثير", "مثيرة", "جسد", "ملابس_داخلية", "لانجيري",
            "سكس_عربي", "سكس_مصري", "سكس_خليجي", "سكس_سوري", "سكس_عراقي",
            "سكس_مغربي", "سكس_جزائري", "سكس_تونسي", "سكس_لبناني",
            "نيك_عربي", "نيك_مصري", "نيك_خليجي", "شرموطة_عربية",
            // --- Arabic (transliteration / latin) ---
            "siks", "siksmisr", "siks3arabi", "sks", "sksy", "neek", "neik",
            "nik", "nikk", "taiz", "teez", "tiz", "kos", "koss", "zob", "zobb",
            "sharmota", "sharmouta", "sharmoota", "sharmuta", "3ahira", "ahira",
            "3areya", "3arian", "3arey", "boor", "boori", "lwat", "sehaq",
            "shahwa", "ibahi", "ebahi", "ebahia", "da3ara", "da3ara", "qahba",
            "qahbeh", "momos", "boghy", "zanya", "zani", "khale3", "mouser",
            "muser", "mthir", "mthira", "jasad", "langerie", "langery",
            "siks arabi", "siks masri", "siks khaleeji", "siks suri",
            "siks iraqi", "siks maghribi", "siks jazairi", "siks tunsi",
            "siks lubnani", "neek arabi", "neek masri", "neek khaleeji",
            "arab sex", "arab porn", "arab nudes", "arab leaks", "arab xxx",
            "arab onlyfans", "arab telegram", "arab snapchat", "arab mega",
            "arab telebox", "arab leaks telegram", "arab nsfw",
            // --- Japanese ---
            "エロ", "えろ", "エロ垢", "エロい", "おっぱい", "ぱいぱい", "おぱい",
            "セックス", "セクロス", "ヌード", "裸", "裸体", "全裸", "半裸",
            "エッチ", "えっち", "痴女", "痴漢", "変態", "フェラ", "手コキ",
            "中出し", "顔射", "潮吹き", "盗撮", "無修正", "アダルト", "成人",
            "18禁", "裏垢", "オナニー", "マンコ", "ちんこ", "おまんこ", "まんこ",
            "巨乳", "爆乳", "美乳", "お尻", "尻", "パンツ", "下着", "ランジェリー",
            "風俗", "デリヘル", "ソープ", "AV女優", "AV男優", "エロ動画",
            "エロ画像", "エロサイト", "セフレ", "援助交際", "パパ活",
            // --- Russian ---
            "секс", "секси", "порно", "порнуха", "голая", "голые", "голый",
            "эротика", "эротик", "интим", "минет", "анал", "сиськи", "сиська",
            "попка", "писька", "член", "вагина", "оргазм", "мастурбация",
            "разврат", "шлюха", "блядь", "сука", "трахать", "трах", "ебать",
            "ебля", "голые_девушки", "голые_парни", "секс_чат", "секс_знакомства",
            // --- Spanish ---
            "sexo", "sexos", "tetas", "tetonas", "culo", "culos", "culona",
            "coño", "cono", "verga", "pene", "vagina", "desnuda", "desnudo",
            "desnudas", "caliente", "cachonda", "orgia", "mamada", "mamadas",
            "puta", "putita", "putas", "zorra", "zorras", "nalgona", "nalgonas",
            "lenceria", "erotico", "erotica", "porno", "pornos", "pajilla",
            "pajillas", "corrida", "corridas", "follando", "follar", "coger",
            "cogiendo", "sexo_anal", "sexo_oral", "pack", "packs", "only",
            // --- Portuguese ---
            "putaria", "putarias", "novinha", "novinhas", "safada", "safadas",
            "safadeza", "buceta", "bucetas", "pau", "paus", "cu", "cus",
            "peitos", "peitao", "nua", "nuas", "nu", "nus", "tesao", "tarada",
            "taradas", "acompanhante", "acompanhantes", "gp", "gps", "piranha",
            "piranhas", "vagabunda", "vagabundas", "transar", "transando",
            "gozar", "gozada", "punheta", "boquete", "anal", "pack", "packs",
            // --- German ---
            "nackt", "nackte", "nackten", "titten", "schwanz", "fotze", "arsch",
            "geil", "geile", "geiler", "pornos", "porno", "erotik", "fick",
            "ficken", "gefickt", "blasen", "blowjob", "muschi", "busen",
            "brüste", "bruste", "sexchat", "sexdate", "sexkontakt",
            // --- French ---
            "sexe", "nue", "nues", "nu", "nichons", "chatte", "bite", "couille",
            "couilles", "salope", "salopes", "pute", "putes", "chienne",
            "baise", "baiser", "baisee", "erotique", "porno", "porn",
            "branlette", "branler", "jouir", "jouissance", "suce", "sucer",
            "sexanal", "sexoral", "plancul", "plan_cul", "nudes", "pack",
            // --- Korean ---
            "야동", "야동사이트", "섹스", "섹스영상", "야사", "노출", "노출증",
            "자위", "자위행위", "성인", "19금", "알몸", "오르가즘", "젖",
            "가슴", "엉덩이", "팬티", "속옷", "란제리", "조건만남", "원나잇",
            "성인영상", "성인사진", "야한", "야한사진",
            // --- Chinese ---
            "色情", "色情片", "裸", "裸照", "裸体", "全裸", "半裸", "情色",
            "成人", "成人片", "成人视频", "18禁", "做爱", "性爱", "性交", "性行为",
            "淫", "淫秽", "黄色", "黄片", "三级", "三级片", "自慰", "约炮",
            "一夜情", "露点", "走光", "偷拍", "无码", "有码", "内射", "口交",
            // --- Hindi ---
            "सेक्स", "अश्लील", "नग्न", "पोर्न", "कामुक", "वासना", "अश्लीलता",
            // --- Turkish ---
            "seks", "seksi", "sikiş", "sikis", "çıplak", "ciplak", "çıplaklar",
            "sik", "sikerim", "orospu", "oruspu", "kaltak", "fahişe", "fahise",
            "meme", "memeler", "göğüs", "gogus", "amcık", "amcik", "penis",
            "vajina", "porno", "pornosu", "erotik", "azgın", "azgin",
            // --- Italian ---
            "sesso", "sessi", "nuda", "nude", "nudo", "tette", "cazzo", "figa",
            "scopare", "scopata", "pompino", "sega", "seghe", "orgasmo",
            "porno", "pornazzi", "erotico", "erotica", "troia", "puttana",
            // --- Polish ---
            "seks", "seksu", "porno", "porno", "naga", "nago", "nagie", "cycki",
            "kutasa", "kutas", "cipka", "cipy", "jebac", "jebanie", "pizda",
            "suka", "kurwa", "sex", "erotyka", "nagie_zdjecia",
            // --- Dutch ---
            "seks", "naakt", "naakte", "tieten", "lul", "kut", "neuken",
            "geneukt", "porno", "erotiek", "geil", "slet", "hoer", "poep",
            // --- Thai ---
            "เสียว", "หลุด", "โป๊", "เปลือย", "เซ็กส์", "เซ็ก", "นม", "หี",
            "ควย", "เย็ด", "เสียวๆ", "คลิปหลุด", "ภาพหลุด", "โป๊เปลือย",
            // --- Vietnamese ---
            "khỏa thân", "khoa than", "lộ hàng", "lo hang", "nude", "sex",
            "khiêu dâm", "khieu dam", "địt", "dit", "buồi", "buoi", "lồn", "lon",
            "clip nóng", "clip nong", "ảnh nóng", "anh nong",
            // --- Indonesian ---
            "bokep", "bokep indo", "telanjang", "mesum", "syur", "ngentot",
            "memek", "kontol", "masturbasi", "colmek", "pewek", "sange",
            "viral", "viral indo", "open bo", "openbo", "bookingan",
            // --- misc / generic ---
            "18+", "21+", "r18", "adult", "adultcontent", "adultvideo",
            "sexwork", "sexworker", "nsfw18", "nsfwgirl", "nsfwboy",
            "hotgirl", "hotgirls", "hotwife", "hotmom", "stepmom", "stepsis",
            "stepbro", "stepdad", "daddy", "mommy", "sugar", "sugarbaby",
            "sugardaddy", "hookup", "hookups", "fwb", "ons", "oneNight",
            "sext", "sexts", "snap", "snapsex", "snapfun", "snapchatsex"
    };

    private static final AtomicBoolean QUERY_LOGGED = new AtomicBoolean(false);
    private static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    private static final AtomicInteger PARAM_NAMES_LOGGED = new AtomicInteger();

    private static final AtomicBoolean HARD_BLOCKED = new AtomicBoolean(false);

    /** "minichatt" -> "minichat", "teenageerr" -> "tenager". */
    private static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char previous = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == previous) {
                continue;
            }
            out.append(c);
            previous = c;
        }
        return out.toString();
    }

    /**
     * True when the query itself is an explicit term. Such a search cannot be
     * cleaned with exclusions ("X -X" is rejected by X), so it is blocked
     * outright instead of returning a feed of explicit posts.
     */
    private static boolean isExplicitQuery(String query) {
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
                continue;
            }
            if (word.length() > 1) {
                String collapsed = collapse(word.toString());
                for (String term : EXCLUDE_TERMS) {
                    if (term.indexOf(' ') >= 0) {
                        continue; // phrases are handled as individual words
                    }
                    String stem = collapse(term.toLowerCase(java.util.Locale.ROOT));
                    if (collapsed.equals(stem)) {
                        return true;
                    }
                    if (stem.length() >= 5 && collapsed.contains(stem)) {
                        return true;
                    }
                }
            }
            word.setLength(0);
        }
        return false;
    }

    /** Spelling variants, so exact-term server exclusions still bite. */
    private static void addVariants(java.util.LinkedHashSet<String> out, String term) {
        String lower = term.toLowerCase(java.util.Locale.ROOT);
        out.add(term);
        if (lower.length() >= 4) {
            out.add(lower + lower.charAt(lower.length() - 1));
            out.add(lower + "s");
            if (lower.contains("ou")) {
                out.add(lower.replaceFirst("ou", "o"));
            }
            if (lower.contains("oo")) {
                out.add(lower.replaceFirst("oo", "o"));
            }
            if (lower.contains("ee")) {
                out.add(lower.replaceFirst("ee", "e"));
            }
            out.add(lower.replaceFirst("a", "aa"));
            out.add(lower.replaceFirst("e", "ee"));
            out.add(lower.replaceFirst("a", ""));
            out.add(lower.replaceFirst("e", ""));
        }
    }

    /**
     * Rewrites the outgoing search query: explicit searches are blocked, all
     * others get the blocklist (plus spelling variants) appended as exclusions
     * so X filters them server-side on every search surface.
     */
    public static String augmentQueryParam(String name, String value) {
        if (PARAM_NAMES_LOGGED.getAndIncrement() < 4) {
            System.err.println("SafeX: param setter name=" + name);
        }
        // X uses "q" on the legacy search endpoint and "query" on GraphQL.
        if (!("q".equals(name) || "query".equals(name)) || value == null || value.isEmpty()) {
            return value;
        }

        if (isExplicitQuery(value)) {
            if (HARD_BLOCKED.compareAndSet(false, true)) {
                System.err.println("SafeX: explicit search blocked");
            }
            return "safexnoresults9f3c";
        }

        java.util.Locale locale = java.util.Locale.ROOT;
        String lower = value.toLowerCase(locale);
        StringBuilder out = new StringBuilder(value);
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (String term : EXCLUDE_TERMS) {
            if (term.indexOf(' ') >= 0) {
                terms.add(term);
            } else {
                addVariants(terms, term);
            }
        }

        int added = 0;
        for (String term : terms) {
            if (added >= 120 || out.length() > 1800) {
                break;
            }
            String lowerTerm = term.toLowerCase(locale);

            // Phrases: exclude every word separately, since "-a b" would only
            // exclude "a" and require "b".
            if (lowerTerm.indexOf(' ') >= 0) {
                for (String part : lowerTerm.split(" +")) {
                    if (part.isEmpty() || lower.contains(part) || lower.contains(" -" + part)) {
                        continue;
                    }
                    out.append(" -").append(part);
                    added++;
                    if (added >= 120 || out.length() > 1800) {
                        break;
                    }
                }
                continue;
            }

            // Never exclude a term the user searched for: "X -X" makes X reject
            // the whole request.
            if (lower.contains(lowerTerm) || lower.contains(" -" + lowerTerm)) {
                continue;
            }
            out.append(" -").append(term);
            added++;
        }

        if (added > 0) {
            QUERY_COUNT.incrementAndGet();
            if (QUERY_LOGGED.compareAndSet(false, true)) {
                System.err.println("SafeX: search exclusions applied (added=" + added + ")");
            }
        }
        return out.toString();
    }

    /** Object-typed entry point for the generic map builder (e0.B). */
    public static Object augmentQueryParamObject(Object name, Object value) {
        if (!(name instanceof String) || !(value instanceof String)) {
            return value;
        }
        return augmentQueryParam((String) name, (String) value);
    }

    /** Diagnostics helper for logging; carries no post content. */
    public static String describe() {
        return "SafeX depth=" + DEPTH.get()[0];
    }
}
