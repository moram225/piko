# SafeX — reproduce

All commands run in Docker on Windows (`rea-workspace:1`, built from
`docker/workspace.Dockerfile`). The workspace is mounted at `/work`.

## 1. Build the patch bundle

Requires a GitHub token with `read:packages` (Morphe artifacts live on
`maven.pkg.github.com/MorpheApp/registry`). The token is passed only via the
process environment:

```powershell
$env:GITHUB_TOKEN = (gh auth token).Trim()
$env:GITHUB_ACTOR = "moram225"
docker run --rm --network bridge -e GITHUB_TOKEN -e GITHUB_ACTOR `
  -v "${PWD}:/work" -v piko-gradle:/home/dev/.gradle -w /work/piko rea-workspace:1 `
  ./gradlew --no-daemon buildAndroid
```

Output: `piko/patches/build/libs/patches-3.10.0.mpp`.

## 2. Confirm the patch is registered

```powershell
docker run --rm -v "${PWD}:/work" rea-workspace:1 `
  morphe list-patches --patches=/work/piko/patches/build/libs/patches-3.10.0.mpp
```

Look for `Name: SafeX: remove sensitive posts`.

## 3. Patch the APK

```powershell
docker run --rm --memory 8g -v "${PWD}:/work" -w /work rea-workspace:1 `
  sh /work/scripts/patch-safex-full.sh
```

> Do **not** use `--exclusive`: it skips extension merging, producing an APK that
> references extension classes it does not contain (see STATUS.md, round 7).

## 4. Verify the output APK

```powershell
docker run --rm --memory 6g -v "${PWD}:/work" rea-workspace:1 `
  sh /work/scripts/verify-final.sh
```

Expected: `SafeX`/`TimelineEntry`/`Utils` found, `beginEntry`/`observeMedia`
markers present, and the decompiled entry parser calling `SafeX.beginEntry()`.

## 5. Run the regression tests

```powershell
docker run --rm -v "${PWD}:/work" -w /work rea-workspace:1 `
  sh /work/scripts/run-safex-tests.sh
```

Expected: `11 passed, 0 failed`.

## 6. On-device confirmation (not yet performed)

```powershell
adb logcat -c
adb logcat | findstr SafeX
# scroll Home, Search Top/Latest, a profile, bookmarks
```

Every removed post should emit `SafeX: sensitive entry dropped (total=N)` and be
absent from the list. The device APK must be reinstalled (different signing key),
which clears the logged-in session.
