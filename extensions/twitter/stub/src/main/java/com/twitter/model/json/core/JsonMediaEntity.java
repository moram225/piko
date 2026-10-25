package com.twitter.model.json.core;

/**
 * Compile-time stub for X 12.19.1 (com.twitter.android, versionCode 312191000).
 *
 * Verified against the shipped DEX via REA/JADX:
 *  - field q (Z) is written by the JSON key "possibly_sensitive"
 *  - field u (JsonSensitiveMediaWarning) is written by "sensitive_media_warning"
 *    and "ext_sensitive_media_warning"
 *
 * See docs/safex/RE-findings.md (F12) for the decompiled evidence.
 */
public class JsonMediaEntity {
    public boolean q;
    public JsonSensitiveMediaWarning u;
}
