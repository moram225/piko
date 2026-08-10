# SafeX

SafeX is a local NSFW-post removal patch for X `12.7.1-release.0`.

## Behavior

- Posts carrying X's native sensitive-media warning are removed at the timeline JSON parser before rendering.
- X-sensitive posts train the local content model with positive weight `1.0`.
- `Mark as NSFW` is added to each post's three-dot menu.
- Manual labels train with positive weight `2.0` and the action is remapped to X's native `IDontLikeThisTweet` path for immediate removal.
- Posts automatically removed by SafeX never train the model, preventing self-reinforcing false positives.
- Posts X does not mark sensitive contribute only weak negative evidence (`0.10`).

## Features

Only post-local features are learned:

- words
- bigrams
- trigrams
- hashtags
- mutation-aware hashtag families
- linked domains

SafeX intentionally does not learn usernames, author IDs, follower counts, account age, or author reputation.

Hashtag matching combines normalization, edit distance and character n-gram similarity so mutations such as `#momson -> #momsonn` and `#chudai -> #chudaii` remain related. Contextual families such as `#teen/#teenage/#teenager` are weak signals rather than unconditional blocks.

All learned state is stored locally in `safex.db` inside the patched X app's private storage.
