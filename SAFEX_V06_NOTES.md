# SafeX v0.6 — Search safety

Target: X `12.7.1-release.0`.

v0.6 addresses Search-specific leaks observed on-device with v0.5.

## Layers

1. Force X Search `optInFiltering=true` (`com.twitter.model.search.c.a`).
2. Keep `AccountSettings.displaySensitiveMedia=false` as a general backstop.
3. Register `com.x.repositories.search.l0` raw query with its generic URT repository.
4. Pass that Search context through both fresh and cached URT list filters.
5. Continue blocking X-native sensitive posts, sensitive media categories, blur/age interstitials, manual blocks and learned post-local features.
6. If a Search query is clearly high-risk and X returns a visual-media post whose own flags/text miss the risk, drop that post locally.

The Search query is context only. It is never positive training evidence and no author/account reputation is learned.

Build marker: `SafeX-v0.6-search-safety`.
