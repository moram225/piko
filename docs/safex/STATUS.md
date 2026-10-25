# SafeX — delivery status (round 4)

Branch: `moram225/piko` → `safex/pre-render-removal` (pushed, commit `8ba9ce45`)
Artifact: [out/safex-piko-3.10.0.mpp](../out/safex-piko-3.10.0.mpp), 8.65 MB
Patched APK (verification build): [out/safex-base.apk](../out/safex-base.apk), 192 MB

## What SafeX does

X's own classification is read at the JSON layer and the **entire timeline entry**
is dropped before it can become a model object:

| X signal | Where it is parsed | SafeX reads |
|---|---|---|
| `possibly_sensitive` | `JsonMediaEntity$$JsonObjectMapper` | `JsonMediaEntity.q` |
| `sensitive_media_warning` | same mapper (key alias) | `JsonMediaEntity.u` |
| `ext_sensitive_media_warning` | same mapper (key alias) | `JsonMediaEntity.u` |
| `adult_content` | `JsonSensitiveMediaWarning` mapper | `.a` |
| `graphic_violence` | `JsonSensitiveMediaWarning` mapper | `.b` |
| `other` | `JsonSensitiveMediaWarning` mapper | `.c` |

Drop path: `JsonTimelineEntry$$JsonObjectMapper.parse` /
`JsonTimelineModuleItem$$JsonObjectMapper.parse` return `null`, which is the
mechanism piko already ships for ads. No cell, interstitial, placeholder or blank
row is created because nothing reaches the adapter.

## Deliverables

| # | Deliverable | Status |
|---|---|---|
| 1 | RE report | [RE-findings.md](RE-findings.md) — F1–F12 with evidence IDs |
| 2 | Data/control flow diagram | [FLOW.md](FLOW.md) |
| 3 | Signal → source → interception table | below + RE-findings |
| 4 | Patch implementation | 4 files, committed |
| 5 | Regression tests | `piko/safex-tests/SafeXPolicyTest.java`, 11/11 pass |
| 6 | DEX patch evidence | injected `beginEntry` / `observeMedia` decompiled from the patched APK |
| 7 | Unverified cases | listed below |
| 8 | MPP artifact | `out/safex-piko-3.10.0.mpp` |

## Verified (static)

- `./gradlew buildAndroid` → BUILD SUCCESSFUL with SafeX.
- `morphe list-patches` → `Index: 128, Name: SafeX: remove sensitive posts`.
- `morphe patch --exclusive -e "SafeX: remove sensitive posts"` on `base.apk` →
  `INFO: Applied: SafeX: remove sensitive posts`.
- Decompiled from the patched APK:
  ```java
  public JsonTimelineEntry parse(h) {
      SafeX.beginEntry();
      return TimelineEntry.checkEntry(parse(h));
  }
  ```
- `JsonMediaEntity$$JsonObjectMapper` contains `SafeX.observeMedia(...)`.
- `SafeX` class present in the patched DEX (`classes18.dex`).
- 11/11 regression tests pass against the real `SafeX.java`.

## NOT verified (explicit)

1. **Runtime behaviour** — no device or emulator in this environment. Whether
   entries actually disappear on Home/Search/Profile is unproven.
2. **Cached timelines** — URT cache hydrates models without re-parsing JSON, so
   this hook does not cover a restart with warm cache. Known gap.
3. **Quoted posts** — a quote's media is parsed inside the same entry parse in
   the URT payload shape we observed, but this is not runtime-confirmed.
4. **Module siblings** — the module itself is kept; the sensitive child entry is
   dropped. Untested on device.
5. **Search Top/Latest** — same `JsonTimelineEntry` mapper is assumed, not proven
   per surface.

## Next work

- Find the cache-hydration interception point (models restored from Strato), add a
  second hook with the same policy.
- Device/emulator run to move items 1–5 from unverified to verified.

## Addendum — warm-cache analysis (round 5)

The URT repository persists only dismissal/seen metadata; no timeline content is
stored. Cold start re-fetches and re-parses through the hooked mappers, so the
JSON hook is expected to cover restarts as well. This is an evidence-based
inference from the repository inventory, not a device observation.

Still unverified: entity-cache hydration for surfaces that may render from the
DB without a fresh GraphQL timeline payload.

## Round 5 addendum — full APKM build

| Artifact | Size | SHA-256 |
|---|---|---|
| `out/safex-apkm.apk` (patched supported APKM) | 375,954,282 B | `39bb1e7c671d462bf6ee452b2f874eadf9f9d3a56dfbf16640c275c8dd3a0004` |
| `out/safex-piko-3.10.0.mpp` (patch bundle) | 8,651,566 B | `71d9169cb060a30e106833eafeb0d91ba6ca027d3b08e902c613f360b9dc8185` |

Patch log: `Merging split APK bundle...` → `Applied: SafeX: remove sensitive posts`.

DEX check on the APKM output: `classes18.dex` contains both `beginEntry` and
`observeMedia` references, matching the `base.apk` verification.

## Round 6 addendum — diagnostics anomaly (disclosed, not hidden)

Attempt: log every drop with a monotonic counter, first via `android.util.Log.d`,
then via `System.err.println` (because AGP release shrinking removes `Log.d`).

Observed:
- The MPP does contain the new code: `extensions/twitter.mpe` carries
  `recordDrop=1, observeMedia=1, beginEntry=1`.
- After patching, the APK's dex contains `beginEntry`, `observeMedia` and the
  `SafeX` class descriptor, but **not** `recordDrop` / `droppedCount`, and no
  `sensitive entry dropped` string.
- Reproduced across two fresh builds (MPP 12:06 and 12:17 UTC), two fresh patch
  runs, and both `Log.d` and `System.err` variants.

Interpretation: the shrinker inlines/removes Java-only members (`recordDrop`,
`droppedCount`, and the call site), while the smali-injected calls
(`beginEntry`, `observeMedia`) are unreachable to it and survive. The drop path
itself is unaffected — `checkEntry` still returns `null` — but runtime logging
cannot be claimed.

Consequence: diagnostics are **compiled into the bundle but not observable in the
patched DEX**, so they are unverified and deliberately not claimed.

Next options:
1. Inject the log call in smali next to `observeMedia` (bypasses the shrinker).
2. Expose `SafeX.droppedCount()` through a settings row rendered by Java code
   that the shrinker cannot remove because a patch references it.
3. Keep diagnostics out of the shipped artifact and rely on the verified static
   evidence.

## Round 7 — CRITICAL correction: `--exclusive` produced broken APKs

The isolation runs (`--exclusive -e "SafeX: remove sensitive posts"`) do **not**
merge the patch bundle's extension classes. Evidence from `out/safex-base-diag.log`:

```
Compiling patched dex files (mode: STRIP_FAST)
Changed 3 of 238574 classes to mutable
Writing 3 new classes to new DEX files
```

No `Copied ... extension classes` step at all, whereas the normal multi-patch run
reports `Copied 3309 unchanged extension classes to 2 DEX files`.

Checked the output APK:
- `Lapp/morphe/extension/twitter/patches/` appears only as **call-site
  references** inside patched X classes.
- `jadx --single-class app.morphe.extension.twitter.patches.SafeX` →
  `Input class not found`.
- `Lapp/morphe/extension/shared/` and `Lapp/morphe/extension/twitter/Pref;` →
  absent.

Consequence: the isolated artifacts (`safex-base.apk`, `safex-apkm.apk`,
`safex-base-diag.apk`) reference a class that is not in the APK and would throw
`NoClassDefFoundError` on the first timeline parse. **They are invalid** and are
superseded by the normal patch run.

This also explains the "stale extension" confusion: there was never an extension
dex in those APKs; only the injected references existed.

## Round 7 correction — the "diagnostics anomaly" was the exclusive-mode bug

With extension merging restored (normal patch run), the artifact contains:

| Check | Result |
|---|---|
| `app.morphe.extension.twitter.patches.SafeX` | present |
| `app.morphe.extension.twitter.patches.TimelineEntry` | present |
| `app.morphe.extension.shared.Utils` | present |
| `beginEntry` call sites | `classes18.dex`, `classes20.dex` |
| `observeMedia` call sites | `classes18.dex`, `classes20.dex` |
| `recordDrop` | `classes20.dex` |
| `"sensitive entry dropped"` string | `classes20.dex` |

So diagnostics **do** work and always did; the isolated runs simply had no
extension dex. The earlier `Log.d` → `System.err` change is retained (it is
harmless and avoids the shrinker question), but the root cause was never the
shrinker.

### Valid artifact

| Artifact | Size | SHA-256 | Status |
|---|---|---|---|
| `out/safex-full.apk` (all patches + SafeX) | 313,951,317 B | `19bb37002815ccfa0d9e4d859d3095c6a028929a1033d48c5c94b272269a151b` | **valid** |
| `out/safex-apkm.apk`, `out/safex-base.apk`, `out/safex-base-diag.apk` | — | — | invalid (no extension dex) |

## Round 7b — patch-interaction fix

The default bundle applies **Show sensitive media**, which zeroes
`JsonSensitiveMediaWarning.a/b/c` — the very flags SafeX reads. It does not clear
`JsonMediaEntity.q` (`possibly_sensitive`), but relying on one of two signals is
weaker than reading both.

Fix (commit `cb0a6396`): `SafeX.active` is set when an entry parse begins, and
`TimelineEntry.showSensitiveMedia(JsonSensitiveMediaWarningPatchInterface)`
skips clearing while SafeX is active. Detection therefore always sees X's
original classification regardless of the user's Reveal setting.

Remaining device-visible consequence: when both patches are applied, the blur
stays for sensitive posts that SafeX drops anyway; non-sensitive posts are
unaffected.

## Round 8 — final artifact and bytecode verification

Final patch run: 73 patches applied, `Copied 3312 unchanged extension classes`.

| Artifact | Size | SHA-256 |
|---|---|---|
| `out/safex-full.apk` | 313,951,317 B | `417ac24c12ee97a789eb91da07a9e4c48505727d2e706279145ddf3a5710029b` |
| `out/safex-piko-3.10.0.mpp` | 8,654,281 B | `d5c13a5e6f125dc8125f2f4cdd4c669aae8dcbcec7e92014eddf1760d5060b9a` |

Decompiled from that APK:

```java
// com.twitter.model.json.timeline.urt.JsonTimelineEntry$$JsonObjectMapper
public JsonTimelineEntry parse(h) {
    SafeX.beginEntry();
    return TimelineEntry.checkEntry(parse(h));
}

// app.morphe.extension.twitter.patches.TimelineEntry
boolean zEndEntry = SafeX.endEntry();
...
System.err.println("SafeX: sensitive entry dropped (total=" + SafeX.recordDrop() + ")");
...
if (showSensitiveMedia && !SafeX.active && jsonSensitiveMediaWarningPatchInterface != null) { ... }
```

Extension classes present: `SafeX`, `TimelineEntry`, `shared.Utils`.
DEX markers: `beginEntry`/`observeMedia` in `classes18.dex` + `classes20.dex`,
`recordDrop` and the drop message in `classes20.dex`.

Runtime behaviour on a device remains **unverified** — no device or emulator is
available in this environment.
