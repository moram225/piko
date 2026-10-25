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
            "Ø³ÙƒØ³", "Ø³ÙƒØ³ÙŠ", "Ø¬Ù†Ø³", "Ø¬Ù†Ø³ÙŠ", "Ù†ÙŠÙƒ", "Ø·ÙŠØ²", "ÙƒØ³", "Ø²Ø¨", "Ø¹Ø§Ù‡Ø±",
            "Ø¹Ø§Ù‡Ø±Ø©", "Ø´Ø±Ù…ÙˆØ·", "Ø´Ø±Ù…ÙˆØ·Ø©", "Ø¹Ø§Ø±ÙŠ", "Ø¹Ø±ÙŠØ§Ù†", "Ø¨ÙˆØ±Ù†", "Ù„ÙˆØ§Ø·",
            "Ø³Ø­Ø§Ù‚", "Ø´Ù‡ÙˆØ©", "Ø¥ØºØ±Ø§Ø¡", "Ù…Ø«Ø§Ø±Ø©", "Ø§Ø¨Ø§Ø­ÙŠ", "Ø¥Ø¨Ø§Ø­ÙŠ", "Ø§Ø¨Ø§Ø­ÙŠØ©",
            "Ø¥Ø¨Ø§Ø­ÙŠØ©", "Ø®Ù„Ø§Ø¹Ø©", "Ø®Ù„Ø§Ø¹ÙŠ", "Ø¯Ø¹Ø§Ø±Ø©", "Ù‚Ø­Ø¨Ø©", "Ù‚Ø­Ø¨", "ÙØ§Ø­Ø´Ø©",
            "Ù…Ù†Ø­Ø±Ù", "Ù…Ù†Ø­Ø±ÙØ©", "Ù…ÙˆÙ…Ø³", "Ø¨ØºÙŠ", "Ø²Ø§Ù†ÙŠØ©", "Ø²Ø§Ù†ÙŠ", "Ø®Ù„ÙŠØ¹",
            "Ù…Ø«ÙŠØ±", "Ù…Ø«ÙŠØ±Ø©", "Ø¬Ø³Ø¯", "Ù…Ù„Ø§Ø¨Ø³_Ø¯Ø§Ø®Ù„ÙŠØ©", "Ù„Ø§Ù†Ø¬ÙŠØ±ÙŠ",
            "Ø³ÙƒØ³_Ø¹Ø±Ø¨ÙŠ", "Ø³ÙƒØ³_Ù…ØµØ±ÙŠ", "Ø³ÙƒØ³_Ø®Ù„ÙŠØ¬ÙŠ", "Ø³ÙƒØ³_Ø³ÙˆØ±ÙŠ", "Ø³ÙƒØ³_Ø¹Ø±Ø§Ù‚ÙŠ",
            "Ø³ÙƒØ³_Ù…ØºØ±Ø¨ÙŠ", "Ø³ÙƒØ³_Ø¬Ø²Ø§Ø¦Ø±ÙŠ", "Ø³ÙƒØ³_ØªÙˆÙ†Ø³ÙŠ", "Ø³ÙƒØ³_Ù„Ø¨Ù†Ø§Ù†ÙŠ",
            "Ù†ÙŠÙƒ_Ø¹Ø±Ø¨ÙŠ", "Ù†ÙŠÙƒ_Ù…ØµØ±ÙŠ", "Ù†ÙŠÙƒ_Ø®Ù„ÙŠØ¬ÙŠ", "Ø´Ø±Ù…ÙˆØ·Ø©_Ø¹Ø±Ø¨ÙŠØ©",
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
            "ã‚¨ãƒ­", "ãˆã‚", "ã‚¨ãƒ­åž¢", "ã‚¨ãƒ­ã„", "ãŠã£ã±ã„", "ã±ã„ã±ã„", "ãŠã±ã„",
            "ã‚»ãƒƒã‚¯ã‚¹", "ã‚»ã‚¯ãƒ­ã‚¹", "ãƒŒãƒ¼ãƒ‰", "è£¸", "è£¸ä½“", "å…¨è£¸", "åŠè£¸",
            "ã‚¨ãƒƒãƒ", "ãˆã£ã¡", "ç—´å¥³", "ç—´æ¼¢", "å¤‰æ…‹", "ãƒ•ã‚§ãƒ©", "æ‰‹ã‚³ã‚­",
            "ä¸­å‡ºã—", "é¡”å°„", "æ½®å¹ã", "ç›—æ’®", "ç„¡ä¿®æ­£", "ã‚¢ãƒ€ãƒ«ãƒˆ", "æˆäºº",
            "18ç¦", "è£åž¢", "ã‚ªãƒŠãƒ‹ãƒ¼", "ãƒžãƒ³ã‚³", "ã¡ã‚“ã“", "ãŠã¾ã‚“ã“", "ã¾ã‚“ã“",
            "å·¨ä¹³", "çˆ†ä¹³", "ç¾Žä¹³", "ãŠå°»", "å°»", "ãƒ‘ãƒ³ãƒ„", "ä¸‹ç€", "ãƒ©ãƒ³ã‚¸ã‚§ãƒªãƒ¼",
            "é¢¨ä¿—", "ãƒ‡ãƒªãƒ˜ãƒ«", "ã‚½ãƒ¼ãƒ—", "AVå¥³å„ª", "AVç”·å„ª", "ã‚¨ãƒ­å‹•ç”»",
            "ã‚¨ãƒ­ç”»åƒ", "ã‚¨ãƒ­ã‚µã‚¤ãƒˆ", "ã‚»ãƒ•ãƒ¬", "æ´åŠ©äº¤éš›", "ãƒ‘ãƒ‘æ´»",
            // --- Russian ---
            "ÑÐµÐºÑ", "ÑÐµÐºÑÐ¸", "Ð¿Ð¾Ñ€Ð½Ð¾", "Ð¿Ð¾Ñ€Ð½ÑƒÑ…Ð°", "Ð³Ð¾Ð»Ð°Ñ", "Ð³Ð¾Ð»Ñ‹Ðµ", "Ð³Ð¾Ð»Ñ‹Ð¹",
            "ÑÑ€Ð¾Ñ‚Ð¸ÐºÐ°", "ÑÑ€Ð¾Ñ‚Ð¸Ðº", "Ð¸Ð½Ñ‚Ð¸Ð¼", "Ð¼Ð¸Ð½ÐµÑ‚", "Ð°Ð½Ð°Ð»", "ÑÐ¸ÑÑŒÐºÐ¸", "ÑÐ¸ÑÑŒÐºÐ°",
            "Ð¿Ð¾Ð¿ÐºÐ°", "Ð¿Ð¸ÑÑŒÐºÐ°", "Ñ‡Ð»ÐµÐ½", "Ð²Ð°Ð³Ð¸Ð½Ð°", "Ð¾Ñ€Ð³Ð°Ð·Ð¼", "Ð¼Ð°ÑÑ‚ÑƒÑ€Ð±Ð°Ñ†Ð¸Ñ",
            "Ñ€Ð°Ð·Ð²Ñ€Ð°Ñ‚", "ÑˆÐ»ÑŽÑ…Ð°", "Ð±Ð»ÑÐ´ÑŒ", "ÑÑƒÐºÐ°", "Ñ‚Ñ€Ð°Ñ…Ð°Ñ‚ÑŒ", "Ñ‚Ñ€Ð°Ñ…", "ÐµÐ±Ð°Ñ‚ÑŒ",
            "ÐµÐ±Ð»Ñ", "Ð³Ð¾Ð»Ñ‹Ðµ_Ð´ÐµÐ²ÑƒÑˆÐºÐ¸", "Ð³Ð¾Ð»Ñ‹Ðµ_Ð¿Ð°Ñ€Ð½Ð¸", "ÑÐµÐºÑ_Ñ‡Ð°Ñ‚", "ÑÐµÐºÑ_Ð·Ð½Ð°ÐºÐ¾Ð¼ÑÑ‚Ð²Ð°",
            // --- Spanish ---
            "sexo", "sexos", "tetas", "tetonas", "culo", "culos", "culona",
            "coÃ±o", "cono", "verga", "pene", "vagina", "desnuda", "desnudo",
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
            "brÃ¼ste", "bruste", "sexchat", "sexdate", "sexkontakt",
            // --- French ---
            "sexe", "nue", "nues", "nu", "nichons", "chatte", "bite", "couille",
            "couilles", "salope", "salopes", "pute", "putes", "chienne",
            "baise", "baiser", "baisee", "erotique", "porno", "porn",
            "branlette", "branler", "jouir", "jouissance", "suce", "sucer",
            "sexanal", "sexoral", "plancul", "plan_cul", "nudes", "pack",
            // --- Korean ---
            "ì•¼ë™", "ì•¼ë™ì‚¬ì´íŠ¸", "ì„¹ìŠ¤", "ì„¹ìŠ¤ì˜ìƒ", "ì•¼ì‚¬", "ë…¸ì¶œ", "ë…¸ì¶œì¦",
            "ìžìœ„", "ìžìœ„í–‰ìœ„", "ì„±ì¸", "19ê¸ˆ", "ì•Œëª¸", "ì˜¤ë¥´ê°€ì¦˜", "ì –",
            "ê°€ìŠ´", "ì—‰ë©ì´", "íŒ¬í‹°", "ì†ì˜·", "ëž€ì œë¦¬", "ì¡°ê±´ë§Œë‚¨", "ì›ë‚˜ìž‡",
            "ì„±ì¸ì˜ìƒ", "ì„±ì¸ì‚¬ì§„", "ì•¼í•œ", "ì•¼í•œì‚¬ì§„",
            // --- Chinese ---
            "è‰²æƒ…", "è‰²æƒ…ç‰‡", "è£¸", "è£¸ç…§", "è£¸ä½“", "å…¨è£¸", "åŠè£¸", "æƒ…è‰²",
            "æˆäºº", "æˆäººç‰‡", "æˆäººè§†é¢‘", "18ç¦", "åšçˆ±", "æ€§çˆ±", "æ€§äº¤", "æ€§è¡Œä¸º",
            "æ·«", "æ·«ç§½", "é»„è‰²", "é»„ç‰‡", "ä¸‰çº§", "ä¸‰çº§ç‰‡", "è‡ªæ…°", "çº¦ç‚®",
            "ä¸€å¤œæƒ…", "éœ²ç‚¹", "èµ°å…‰", "å·æ‹", "æ— ç ", "æœ‰ç ", "å†…å°„", "å£äº¤",
            // --- Hindi ---
            "à¤¸à¥‡à¤•à¥à¤¸", "à¤…à¤¶à¥à¤²à¥€à¤²", "à¤¨à¤—à¥à¤¨", "à¤ªà¥‹à¤°à¥à¤¨", "à¤•à¤¾à¤®à¥à¤•", "à¤µà¤¾à¤¸à¤¨à¤¾", "à¤…à¤¶à¥à¤²à¥€à¤²à¤¤à¤¾",
            // --- Turkish ---
            "seks", "seksi", "sikiÅŸ", "sikis", "Ã§Ä±plak", "ciplak", "Ã§Ä±plaklar",
            "sik", "sikerim", "orospu", "oruspu", "kaltak", "fahiÅŸe", "fahise",
            "meme", "memeler", "gÃ¶ÄŸÃ¼s", "gogus", "amcÄ±k", "amcik", "penis",
            "vajina", "porno", "pornosu", "erotik", "azgÄ±n", "azgin",
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
            "à¹€à¸ªà¸µà¸¢à¸§", "à¸«à¸¥à¸¸à¸”", "à¹‚à¸›à¹Š", "à¹€à¸›à¸¥à¸·à¸­à¸¢", "à¹€à¸‹à¹‡à¸à¸ªà¹Œ", "à¹€à¸‹à¹‡à¸", "à¸™à¸¡", "à¸«à¸µ",
            "à¸„à¸§à¸¢", "à¹€à¸¢à¹‡à¸”", "à¹€à¸ªà¸µà¸¢à¸§à¹†", "à¸„à¸¥à¸´à¸›à¸«à¸¥à¸¸à¸”", "à¸ à¸²à¸žà¸«à¸¥à¸¸à¸”", "à¹‚à¸›à¹Šà¹€à¸›à¸¥à¸·à¸­à¸¢",
            // --- Vietnamese ---
            "khá»a thÃ¢n", "khoa than", "lá»™ hÃ ng", "lo hang", "nude", "sex",
            "khiÃªu dÃ¢m", "khieu dam", "Ä‘á»‹t", "dit", "buá»“i", "buoi", "lá»“n", "lon",
            "clip nÃ³ng", "clip nong", "áº£nh nÃ³ng", "anh nong",
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

    /** Query-safe form of a term: letters, digits and spaces only. */
    private static String sanitize(String term) {
        StringBuilder out = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /** Spelling variants, so exact-term server exclusions still bite. */
    private static void addVariants(java.util.LinkedHashSet<String> out, String term) {
        String clean = sanitize(term);
        if (clean.length() < 3) {
            return;
        }
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        out.add(lower);
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
        java.util.LinkedHashSet<String> bases = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> phraseWords = new java.util.LinkedHashSet<>();
        for (String term : EXCLUDE_TERMS) {
            String clean = sanitize(term);
            if (clean.length() < 3) {
                continue;
            }
            String lc = clean.toLowerCase(locale);
            if (lc.indexOf(' ') >= 0) {
                for (String part : lc.split(" +")) {
                    if (part.length() >= 3) {
                        phraseWords.add(part);
                    }
                }
            } else {
                bases.add(lc);
            }
        }

        int added = 0;

        // Pass 1: distinct stems, so the cap covers many terms instead of a few
        // stems with all their spelling variants.
        for (String base : bases) {
            if (added >= 40 || out.length() > 1100) {
                break;
            }
            if (lower.contains(base) || lower.contains(" -" + base)) {
                continue;
            }
            out.append(" -").append(base);
            added++;
        }
        for (String word : phraseWords) {
            if (added >= 40 || out.length() > 1100) {
                break;
            }
            if (lower.contains(word) || lower.contains(" -" + word)) {
                continue;
            }
            out.append(" -").append(word);
            added++;
        }

        // Pass 2: spelling variants of those stems, if budget remains.
        for (String base : bases) {
            if (added >= 40 || out.length() > 1100) {
                break;
            }
            java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
            addVariants(variants, base);
            for (String variant : variants) {
                if (added >= 40 || out.length() > 1100) {
                    break;
                }
                if (variant.equals(base)) {
                    continue;
                }
                if (lower.contains(variant) || lower.contains(" -" + variant)) {
                    continue;
                }
                out.append(" -").append(variant);
                added++;
            }
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
