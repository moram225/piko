# SafeX — implementation design (v0.1 draft)

Status: design only. Nothing below is compiled, patched or run yet.

## Objective

Whenever X itself classifies a post as sensitive, remove the **whole post** before
the UI receives it. Never show the blur, the interstitial, or a placeholder.

## Signals to intercept (from RE findings)

| ID | Signal | Location | Confidence |
|---|---|---|---|
| S1 | `sensitive_media_warning` booleans | `JsonSensitiveMediaWarning.a/b/c` → mapper `parseField2` | observed class shape; key mapping unresolved |
| S2 | `possibly_sensitive` | `com.x.models.CanonicalPost.isPossiblySensitive` (field + getter), populated from tweet JSON | flag exists on the model; JSON source unresolved |
| S3 | media visibility results | `JsonBlurredImageInterstitial`, `BlurImageInterstitial` | class inventory observed |
| S4 | age / native interstitials | `SensitiveMediaBlurPreviewInterstitialView*` delegates | class inventory observed |

S2 is the authoritative post-level classification and is serialized with the
model, so it is the flag that must drive the decision. S1/S3/S4 explain *why* the
UI shows a warning and are secondary confirmations.

## Hook layers

### Layer A — network JSON (provable today)

`JsonTimelineEntry$$JsonObjectMapper.parse` and
`JsonTimelineModuleItem$$JsonObjectMapper.parse` already receive piko's
`TimelineEntry.checkEntry(...)` injection, and returning `null` drops the entry
before the model is built (production-proven for ads).

SafeX extends `checkEntry` with a sensitivity test:

```
checkEntry(entry):
    if pikoFilters(entry) -> null
    if SafeX.isSensitive(entry) -> null      // new
    return entry
```

`isSensitive` must read the parsed JSON graph. The unresolved part is the exact
field chain from `JsonTimelineEntry.c` to the nested tweet and its
`possibly_sensitive`/warning booleans.

### Layer B — cached / hydrated models (unresolved)

`CanonicalPost` is a kotlinx-serialization model, so a cached post carries
`isPossiblySensitive` without ever being re-parsed from JSON. A JSON-only hook
therefore cannot satisfy the "cached posts stay hidden after restart" requirement.

Two candidate interception points still need RE:

1. the URT cache hydration that turns stored models into timeline entries;
2. the timeline assembly that merges fresh + cached entries into the adapter list.

Whichever exists, the filter must run there with the same policy, or cached
sensitive posts will reappear — the exact failure listed for v0.2–v0.6.1.

## Patch structure

```
patches/src/main/kotlin/app/crimera/patches/twitter/timeline/safex/
    SafeXPatch.kt          bytecodePatch, dependsOn(timelineEntryHookPatch)
extensions/twitter/src/main/java/app/morphe/extension/twitter/patches/
    SafeX.java             isSensitive(entry) + diagnostics
    TimelineEntry.java     checkEntry() calls SafeX before returning
```

`SafeXPatch` injects nothing new if the existing entry hook already routes through
`checkEntry`; it validates the fingerprints are present and enables a settings
flag. If Layer B needs its own site, a second `Fingerprint` is added there.

## Diagnostics

- `Log.d("SafeX", "drop entryId=<id> reason=<signal>")` — id only, never text or
  media.
- A counter exposed in piko settings: `sensitiveDropped`.
- Any exception in `isSensitive` must **fail closed**: drop the entry and log,
  because showing a sensitive post is the failure mode we refuse.

## Tests

1. Pure policy function `SafeXPolicy.isSensitive(flags)` with JUnit:
   - any flag true → drop
   - all flags false → keep
   - null / missing model → keep (never drop unrelated posts)
   - module child with sensitive child → drop child only
2. Descriptor/register tests for the injected smali are validated by building and
   re-decompiling the output APK (Layer A).

## Verification ladder (none executed yet)

1. `./gradlew buildAndroid` → `.mpp` — **blocked**: `app.morphe.patches` needs
   `read:packages`.
2. `morphe patch -p safex.mpp base.apk` → patched APK, check exit + patch log.
3. jadx the patched APK, confirm the injected call exists at the parse return and
   registers match (`v`/`p` widths, `move-result-object`).
4. Runtime: not possible in this environment — must be reported as unverified.

## Open RE tasks (next rounds)

- [ ] Field chain: `JsonTimelineEntry.c` → content → tweet → `possibly_sensitive`.
- [ ] `parseField2` key→boolean mapping (decompile timed out once).
- [ ] `CanonicalPost.<init>` parameter order → which boolean is
      `isPossiblySensitive`.
- [ ] Cache hydration path for timeline entries.
- [ ] Search Top/Latest vs Home: same entry mapper or different?
- [ ] Quoted/reposted nested posts: nested `CanonicalPost` or same flag on parent?
- [ ] Does any piko patch (e.g. `Show sensitive media`) clear the flag before
      SafeX sees it? Ordering of extensions matters.
