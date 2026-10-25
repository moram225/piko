# SafeX — RE findings (X 12.19.1-release.0, versionCode 312191000)

Artifact: `/work/apks/base.apk` (extracted base of the 36-split APKM)
SHA-256: `5227c642da6a921c15c89df638e1d591f01d5580d7552bee443da3312b1131b6`
Provider: jadx-headless-mcp 0.7.1 (REA), 133,766 classes, 6,815 resources.

> Version note: the task brief names 12.7.1 / 312071000. The workspace contains
> only 12.19.1 / 312191000, which is also the build piko 3.10.0 targets. All
> findings below are for 12.19.1 and must not be assumed for 12.7.1.

## Evidence ledger

| # | Question | Operation | Evidence |
|---|---|---|---|
| 1 | Which classes mention sensitive media | `search_android_classes` | `ev_0cb133bb929c885f8918e81d6ec1d129c9a8b7b831fa1b6bdf88e00ec4b056ae` |
| 2 | JSON warning model shape | `inspect_android_class` JsonSensitiveMediaWarning | `ev_4c3782604f6b6f7b9453c80ecccf13ea9fb34246abc6639f770e165aae825c92` |
| 3 | Timeline entry JSON model shape | `inspect_android_class` JsonTimelineEntry | `ev_5fdce37ba2cf66a747c296b40e859ec85c984fd46f55f7f0b6f1927de6b3d113` |
| 4 | Inner/model naming inventory | `search_android_classes` JsonTimelineEntry | `ev_f2647369a0b637c0bdf56e716eab289cae3034cbc89884829be788c935284a70` |
| 5 | Canonical post model | `inspect_android_class` CanonicalPost | `ev_7328830fcd3e80ff00cbd89b6fdddc87302fb92cf85e6da7c73471e151549fb2` |

## Observed facts

### F1. JSON warning model (network layer)
`com.twitter.model.json.core.JsonSensitiveMediaWarning`
- 3 fields, all `boolean`: `a`, `b`, `c`
- one no-arg constructor only
- parsed by `JsonSensitiveMediaWarning$$JsonObjectMapper.parse` (confirmed present in class inventory)

Piko's `ShowSensitiveMediaPatch` already hooks that mapper and calls an injected
`patch_showSensitiveMedia()` that writes `0` to **every** boolean field. That is
exactly the behaviour SafeX must not reproduce: it erases the classification.

### F2. Timeline entry JSON wrapper
`com.twitter.model.json.timeline.urt.JsonTimelineEntry`
- fields: `a` String (entry id, used by piko's `checkEntry`), `b` long,
  `c` `JsonTimelineEntry$a` (opaque inner type), `d` long
- methods: `<init>()`, `r()` → `com.twitter.model.timeline.urt.a2`,
  `s(JsonTimelineItem, String, boolean, m0)` → `n2`
- parse entry point: `JsonTimelineEntry$$JsonObjectMapper.parse`

`JsonTimelineModuleItem` (module children) has only `a` String in piko's stub,
with parse entry `JsonTimelineModuleItem$$JsonObjectMapper.parse`.

### F3. Model-level flag survives caching
`com.x.models.CanonicalPost`
- field `isPossiblySensitive` : `boolean`
- method `isPossiblySensitive()` : `boolean` (DEX `()Z`)
- 113 methods; kotlinx.serialization `write$Self` present, i.e. the model is
  serialized/cached — so this flag exists on hydrated objects too, not only on
  freshly parsed JSON.

### F4. Existing drop mechanism (reuse, do not reinvent)
`TimelineEntryHookPatch` injects into both parse mappers at the `RETURN_OBJECT`
and calls `TimelineEntry.checkEntry(...)`; returning `null` drops the entry
before the model reaches the UI. This is proven in production for piko ads.
Files:
- `patches/.../ads/timelineEntryHook/TimelineEntryHookPatch.kt`
- `patches/.../ads/timelineEntryHook/HideAds.kt`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/patches/TimelineEntry.java`

## Open questions (being resolved)

1. Which of `JsonSensitiveMediaWarning.a/b/c` map to `sensitive_media_warning`,
   `ext_sensitive_media_warning`, and the third flag?
2. Where does `CanonicalPost.isPossiblySensitive` get populated from JSON, and
   which consumers read it (renderer vs repository)?
3. What is the cached/hydration path, and does it re-parse JSON or rebuild the
   model directly?
4. Do Search Top/Latest and module children use the same `JsonTimelineEntry`
   mapper as Home?
5. Are quoted/reposted sensitive posts represented as a nested post with its own
   `CanonicalPost`, so the flag is only reachable through the quote relation?

## Not yet verified (must not be claimed)

- Runtime behaviour of any hook.
- Coverage of cached timelines.
- Whether returning `null` from the module-item parse is safe for every module
  shape (piko only does this for ads).
- DEX-level validity of the SafeX injection (build blocked: GitHub Packages
  credential required for `app.morphe.patches`).

## Round 2 additions

### F5. Warning mapper internals
`com.twitter.model.json.core.JsonSensitiveMediaWarning$$JsonObjectMapper` has 7 methods:
- `parse(JsonParser)` twice: a typed overload (index 0, returns
  `JsonSensitiveMediaWarning`) and the Object-returning bridge (index 1), which is
  the one piko hooks.
- `parseField(JsonSensitiveMediaWarning, String, JsonParser)` and
  `parseField2(...)`: the JSON-key dispatch. `parseField2` is where each key name
  is matched to field `a`/`b`/`c`.
- `serialize` / `serialize2`: the warning is written back out, i.e. it round-trips
  through the cache.

Evidence: attempt recorded in-session; `parseField2` decompilation exceeded the
provider's 90 s deadline, so the key→field mapping is still **unresolved**. Retry
with a narrower request or accept symbol-level evidence only.

### F6. Field-level observation
`JsonSensitiveMediaWarning` declares exactly three booleans and no other state.
A post that is sensitive *without* a warning object therefore carries its flag
elsewhere (`possibly_sensitive` on the tweet JSON), so a warning-only hook cannot
be complete.

### F7. Build feasibility (blocked)
`./gradlew tasks` inside the container fails at `settings.gradle.kts:24`:
`Plugin [id: 'app.morphe.patches', version: '1.3.4'] was not found` in
Gradle Plugin Portal, Google, and `maven.pkg.github.com/MorpheApp/registry`.
The registry requires `read:packages`; the current `gh` token has
`gist, read:org, repo, workflow`. No compilation, DEX verification, Morphe run or
MPP artifact is possible until that scope is added.

## Status of the version question
Resolved with the user: target is **12.19.1-release.0 / 312191000**, the only
APKM in the workspace and the build piko 3.10.0 supports.

## Round 3–4: model chain + classification semantics

### F8. What the three warning booleans mean (smali, authoritative)
`JsonSensitiveMediaWarning$$JsonObjectMapper.parseField2` is a plain
`const-string`/`equals` chain writing one field per JSON key:

| JSON key | DEX field | offset in method |
|---|---|---|
| `adult_content` | `JsonSensitiveMediaWarning.a` | `.line 3`, `iput-boolean ...->a:Z` |
| `graphic_violence` | `JsonSensitiveMediaWarning.b` | `.line 5`, `iput-boolean ...->b:Z` |
| `other` | `JsonSensitiveMediaWarning.c` | `.line 7`, `iput-boolean ...->c:Z` |

Each branch calls `JsonParser.q()` (boolean read) and returns immediately.
Evidence: `ev_ad141beddfa68904c2a1e52685a9ce9321803f5b1e74351acd227e9d3a4ec433`
(representation `smali`, marker `java_body_unavailable`).

Consequence: X's media classification is three *independent* booleans. SafeX must
treat **any** of them true as sensitive — exactly what piko's `showSensitiveMedia`
zeros out, in reverse.

### F9. URT JSON wrapper chain (fields, observed)
| Class | Fields that matter | Evidence |
|---|---|---|
| `...urt.JsonTimelineEntry` | `a` String (entryId), `c` `JsonTimelineEntry$a` (content) | `ev_5fdc…b3d113` |
| `...urt.JsonTimelineItem` | `a` `JsonTimelineEntry$b`, `b/c/d` converted model types | `ev_c0a81bcd0c494924325518eb27611065985f0c36ffe91c867f3cce93a3fc81d1` |
| `...urt.JsonTimelineTweet` | 22 fields; `a` `core.i0$a` (tweet union), `m`/`n` booleans | `ev_fa6872ba53d69da4ecdd750f94456a2502aaf31cae589f90ad57cdebbc9466c7` |
| `...core.JsonTweetResults` | `a` `core.i0$a` only; `r()` returns same | `ev_9af3920db20e8edbf5af20a169c2f1c3349980052c89b0838bc5f68fe6ca3f3e` |
| `...core.JsonTweetWithVisibilityResults` | `a` `core.b` (tweet), `g` `mediavisibility.g` (visibility results) | `ev_dcba5511192b6e26c7a33ae7ee47c38df02ff279002e7fab4e5a83296a0d673d` |
| `...mediavisibility.JsonMediaVisibilityResults` | `a` `mediavisibility.f`, `b` `mediavisibility.b`; `r()` → `g` | `ev_5f0da09f01089634ec1e2dcaf922911f612f9d7f5a5361755ef5b47009f72287` |

Note: several DTO fields already hold **converted model** types, not raw JSON, so
the JSON layer is only partially raw. A hook that expects primitive booleans in
the DTO graph must call the model getters instead.

### F10. `i0` / `b` are not the post model
- `com.twitter.model.core.i0` is a converter (4 static methods: `a(i0$a)→b$a`,
  `b(i0)→b`, `c(i0$a)→b`, `d(i0$a)→i0`), zero fields.
  Evidence: `ev_1cdb5f1e100e6a14b73be03ec81c96d60235f07acb1487fc72a9bd2d36d6f0a2`.
- `com.twitter.model.core.b` is an 8-field value type with `getId()` and a builder,
  not the tweet itself.
  Evidence: `ev_7883df180fbea2c8094339a0751e84873486f48fe4e61a4d354c1e98d729063a`.

So `CanonicalPost` (`com.x.models`) is the modern post model and is where
`isPossiblySensitive` lives; the JSON DTO graph reaches it only through
conversion, not by direct field access.

### Signal table (preliminary)
| Signal | Source | Strength | Status |
|---|---|---|---|
| `adult_content` / `graphic_violence` / `other` | `JsonSensitiveMediaWarning.a/b/c` | X's own media classification | **confirmed** (smali) |
| `possibly_sensitive` | `CanonicalPost.isPossiblySensitive` (model, serialized) | X's post-level classification | field confirmed; JSON origin unresolved |
| media visibility interstitial | `JsonTweetWithVisibilityResults.g` | X decided to warn | class confirmed |
| blur interstitial | `JsonBlurredImageInterstitial`, `BlurImageInterstitial` | render-time warning | class confirmed |

### F11. Navigation tooling (inside Docker)
`jadx` CLI 1.5.6 installed at `/work/tools/jadx` (sha256
`545ea2be…230e8974`, the release that fixes CVE-2026-42049). Supports
`--single-class`; a full `--no-res` decompile of the base APK is running to make
the remaining questions grep-able. REA remains the evidence source for every
claim above.

## F12. FINAL: where X actually stores sensitive classification (decompiled source)

`com.twitter.model.json.core.JsonMediaEntity` is the carrier. Decompiled
`JsonMediaEntity$$JsonObjectMapper` (full jadx source tree, /work/out/jadx-src):

```java
if ("possibly_sensitive".equals(str)) {
    jsonMediaEntity.q = hVar.q();          // boolean
    return;
}
if ("sensitive_media_warning".equals(str) || "ext_sensitive_media_warning".equals(str)) {
    jsonMediaEntity.u = JsonSensitiveMediaWarning$$JsonObjectMapper.parse(hVar);
    return;
}
```

So:
- `JsonMediaEntity.q`      = `possibly_sensitive`
- `JsonMediaEntity.u`      = `sensitive_media_warning` **or** `ext_sensitive_media_warning`
- `JsonSensitiveMediaWarning.a/b/c` = `adult_content` / `graphic_violence` / `other`

This is X's complete media sensitivity model at the JSON layer, and it is
reachable from every URT timeline entry because media entities are nested in the
tweet payload parsed by `JsonTimelineEntry$$JsonObjectMapper.parse`.

## Implementation (branch safex/pre-render-removal)

| File | Role |
|---|---|
| `extensions/twitter/stub/.../JsonMediaEntity.java` | compile-time stub for fields `q`, `u` |
| `extensions/twitter/.../patches/SafeX.java` | per-thread entry frame stack; `beginEntry` / `endEntry` / `observeMedia` |
| `extensions/twitter/.../patches/TimelineEntry.java` | `checkEntry(...)` closes the frame and returns `null` to drop |
| `patches/.../timeline/safex/SafeXPatch.kt` | injects `beginEntry` at parse start (entry + module item) and `observeMedia` at media parse return |

Drop mechanism is piko's proven one: returning `null` from the JSON parse means
the entry never becomes a model object and never reaches the adapter.

## Verified so far

- `./gradlew buildAndroid` → BUILD SUCCESSFUL (3m23s) with SafeX in the bundle.
- `morphe list-patches` shows `Index: 128  Name: SafeX: remove sensitive posts`.
- `morphe patch --exclusive -e "SafeX: remove sensitive posts"` on `base.apk` →
  `INFO: Applied: SafeX: remove sensitive posts`, output `/work/out/safex-base.apk`.
- DEX injection check: `scripts/verify-safex.sh`.

## Not verified / open

- Runtime behaviour on a device (no device/emulator here): whether entries are
  actually dropped on Home/Search/Profile is **unverified**.
- Cached/hydrated timelines (models restored without JSON parse) are **not**
  covered by this hook; needs a second interception point.
- Modules: a sensitive child drops the child entry; the module itself is kept
  unless its own frame is marked.
- Quoted posts: a sensitive quote marks the outer entry frame only if the quoted
  media is parsed nested inside the same entry parse; needs runtime confirmation.

## F13. Is there a persisted timeline-content cache? (warm-start question)

Inventory of `com/x/repositories/urt/` (the URT repository package) shows only
metadata persistence:

| Class | Persists |
|---|---|
| `PendingTimelineDismissalStoreImpl` | dismissed-entry bookkeeping |
| `SeenPostsCacheImpl` | seen post ids / locations |

There is **no timeline-content store**: no serialized `UrtTimelineItem` list, no
cached GraphQL timeline payload, no class named for timeline persistence.

Consequence: a cold start re-requests the timeline through the same GraphQL/URT
JSON mappers that SafeX hooks, so entries are re-parsed and the sensitive flag is
re-evaluated. The "cached posts survive filtering" failure of previous versions
is therefore most plausibly explained by filtering *after* parse (model/UI stage)
rather than by a separate persisted path.

Residual risk (unverified): entity-level DB hydration could populate the model
without the JSON media flags for some surface. This would be visible only on a
device with a warm entity cache.

Evidence: directory inventory of the decompiled tree (`/work/out/jadx-src`) plus
`grep -rln 'TimelineCache|CachedTimeline' com/x com/twitter/model`.

## F14. Surface coverage: all timelines share one entry parser

`com.x.models.timelines.TimelineType` enumerates 61 surfaces, including:

FOR_YOU, FOLLOWING, CONVERSATION, USER_PROFILE_POSTS_ONLY,
USER_PROFILE_POSTS_AND_REPLIES, USER_PROFILE_MEDIA/PHOTOS/VIDEOS/REPOSTS/FAVS,
BOOKMARKS, BOOKMARK_COLLECTION, SEARCH_TOP, SEARCH_LATEST, SEARCH_MEDIA,
LIST_POSTS, COMMUNITY_DETAIL_POSTS, COMMUNITY_MEDIA, COMMUNITIES_HOME,
EXPLORE_FOR_YOU, VIDEO_TAB, NOTIFICATIONS, TOPIC.

The repository layer (`com/x/repositories/urt/*`) is parameterised by this enum:
`d`, `e0`, `i0`, `k`, `p`, `y0` all take `TimelineType`. Call sites show one
generic pipeline for many surfaces, e.g. in `com/x/composer/work/SubmitPostWork`:

```java
kotlin.collections.f.i(
    com.x.models.timelines.p.a(TimelineType.FOR_YOU, new String[0]),
    com.x.models.timelines.p.a(TimelineType.FOLLOWING, new String[0]));
```

and `com/x/googlessp/y0.java` groups CONVERSATION / EXPLORE_FOR_YOU / FOR_YOU /
USER_PROFILE_POSTS_ONLY / VIDEO_TAB through the same branch.

There is exactly one `JsonTimelineEntry` DTO and one
`JsonTimelineEntry$$JsonObjectMapper`, referenced only inside
`com.twitter.model.json.timeline.urt` (`JsonTimelineOperation`,
`JsonTimelineItem`, `JsonTimelineModule`, `JsonAddToModuleInstruction`).

Conclusion: any surface rendered as a URT timeline — Home, Search Top/Latest,
profiles, bookmarks, lists, communities, topic, video tab — deserialises entries
through the single mapper SafeX hooks, so the drop applies uniformly.

Edge case not covered by this argument: opening a single post directly (detail
screen / deep link) is not a timeline entry parse, so a sensitive post reached
that way is not dropped by SafeX. Same for any surface that renders a post
without a URT entry wrapper. Untested on device.

## F15. SECOND SIGNAL: media_visibility_results (age / adult gate)

Device testing showed posts rendering X's "Content warning: Adult Content —
Verify Age" card that the warning-based hook did not catch. Those posts are not
`JsonSensitiveMediaWarning`; they carry a separate object.

Decompiled evidence:

- `JsonTweetWithVisibilityResults` field `g` is annotated
  `@JsonField(name = {"media_visibility_results"})` and parsed by
  `LoganSquare.typeConverterFor(com.twitter.model.mediavisibility.g.class)`.
- `JsonMediaVisibilityResults$$JsonObjectMapper` parses `all_media` and
  `blurred_image_interstitial`.
- `JsonBlurredImageInterstitial$$JsonObjectMapper` parses `interstitial_action`,
  `opacity`, `text`, `title`, `available_verification_options` — i.e. the exact
  "Content warning / Verify Age" card.

Therefore: parsing `JsonMediaVisibilityResults` means X restricted this post's
media for age/adult reasons. SafeX now marks the current entry frame when that
object is parsed, in addition to the per-media flags.

Hook: `JsonMediaVisibilityResults$$JsonObjectMapper.parse` →
`SafeX.observeVisibility()`, injected before `RETURN_OBJECT`.

Tests: two new cases (drop on visibility, no leak outside an entry) → 13/13.
