# SafeX — reverse-engineering and implementation report

Target: X (Twitter) `com.twitter.android` **12.19.1**, versionCode **312191000**
Patch base: piko **3.10.0**, Morphe patches **1.14.1**, patcher **1.19.0**
Branch: `moram225/piko` → `safex/pre-render-removal`

## 1. Objective

Remove every post that X itself classifies as sensitive **before it renders**:
no blurred media, no "sensitive content" interstitial, no blank row. Out of scope
by request: local NSFW heuristics, hashtag/image AI, query-based filtering.

## 2. Method

- All analysis executed inside Docker (`rea-workspace:1`).
- **REA** (reverse-engineering agent, 6.7.0) with the JADX headless provider for
  evidence-bearing observations (class shapes, method bodies, reference traces).
- **jadx 1.5.6** CLI for full-source navigation (`--no-res`, 133k classes), used
  to locate JSON key wiring that class-shape inspection cannot reveal.
- Morphe CLI for patch assembly and application; decompilation of the *output*
  APK for bytecode verification.

## 3. Where X classifies content (the core finding)

`com.twitter.model.json.core.JsonMediaEntity` carries the classification, and its
`$$JsonObjectMapper` assigns it from JSON (decompiled):

```java
if ("possibly_sensitive".equals(str)) {
    jsonMediaEntity.q = hVar.q();                  // boolean
    return;
}
if ("sensitive_media_warning".equals(str) || "ext_sensitive_media_warning".equals(str)) {
    jsonMediaEntity.u = JsonSensitiveMediaWarning$$JsonObjectMapper.parse(hVar);
    return;
}
```

`JsonSensitiveMediaWarning` is three independent booleans (smali-verified):

| JSON key | DEX field |
|---|---|
| `adult_content` | `a` |
| `graphic_violence` | `b` |
| `other` | `c` |

Model-level counterparts exist (`com.x.models.CanonicalPost.isPossiblySensitive`,
serialized) but are reached only through conversion; the JSON layer is the
earliest common point for network timelines.

## 4. Interception design

```
URT JSON → JsonTimelineEntry.parse ──beginEntry()──┐
             │                                     │  per-thread frame stack
             ├─ nested media parse ──observeMedia()─┤  marks frame when X flagged
             └─ return ──checkEntry()── endEntry() ─┘
                                   │
                        sensitive → return null
                                   │
                        entry never becomes a model/cell
```

- **Drop mechanism**: returning `null` from the entry parser — the same path piko
  already ships for ads, so the timeline handles a missing entry.
- **Scope safety**: media parsed outside an entry (depth 0) never marks a frame;
  frames are capped at depth 32; unbalanced parses return "not sensitive".
- **Patch interaction**: piko's *Show sensitive media* is enabled by default and
  zeroes the warning booleans. `SafeX.active` makes `showSensitiveMedia()` skip
  clearing while SafeX is parsing, so detection always sees X's original flags.

## 5. Files

| File | Role |
|---|---|
| `patches/.../twitter/timeline/safex/SafeXPatch.kt` | fingerprints + smali injection |
| `extensions/twitter/.../patches/SafeX.java` | frame stack, signal evaluation, counter |
| `extensions/twitter/.../patches/TimelineEntry.java` | `checkEntry` drop + guard + diagnostics |
| `extensions/twitter/stub/.../JsonMediaEntity.java` | field stub (`q`, `u`) |
| `safex-tests/SafeXPolicyTest.java` | 11 regression tests against the shipped class |

## 6. Verification (static, on the shipped artifact)

`out/safex-full.apk` — 73 patches, 3312 extension classes merged.

Decompiled from that APK:

```java
public JsonTimelineEntry parse(h) {
    SafeX.beginEntry();
    return TimelineEntry.checkEntry(parse(h));
}
```

```java
boolean zEndEntry = SafeX.endEntry();
System.err.println("SafeX: sensitive entry dropped (total=" + SafeX.recordDrop() + ")");
if (showSensitiveMedia && !SafeX.active && jsonSensitiveMediaWarningPatchInterface != null) { ... }
```

Presence checks: `SafeX`, `TimelineEntry`, `shared.Utils` classes; `beginEntry` /
`observeMedia` in `classes18.dex` and `classes20.dex`; `recordDrop` and the drop
message in `classes20.dex`.

Tests: 11/11 pass, covering each signal, clean media, out-of-entry leakage,
nested child vs parent, sequential entries, non-media objects, unbalanced frames.

## 7. Coverage argument

`TimelineType` enumerates 61 surfaces (Home, Following, Search Top/Latest/Media,
profiles, bookmarks, lists, communities, topic, video tab…), all executed through
the same URT repository parameterised by that enum, and there is exactly one
`JsonTimelineEntry` DTO/mapper. Any surface rendered as a URT timeline therefore
goes through the hooked parser.

## 8. Limitations (explicit)

1. **Runtime unverified.** No device/emulator was available during the work; all
   claims above are static verification of the exact artifact.
2. **Post detail / deep link** is not a timeline entry parse; SafeX does not drop
   a sensitive post opened directly by URL.
3. **Entity-cache hydration** for surfaces that might render from the DB without
   a fresh timeline payload is not proven absent.
4. **Notifications** and other non-timeline post renderers are unproven.

## 9. Conclusion

SafeX implements X's own classification as the sole predicate, at the earliest
shared parse point, using the drop path the mod ecosystem already trusts. The
artifact is built, applied, and verified down to the decompiled call site. What
remains is runtime confirmation on a real account — the one thing this
environment cannot provide.
