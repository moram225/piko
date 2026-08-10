/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * SafeX private-bundle modification: this upstream patch is intentionally a
 * no-op. SafeX must never make X more permissive, even if the patch is selected
 * accidentally or an old selection is restored by Morphe.
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.sensitivemediasettings

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val sensitiveMediaPatch =
    bytecodePatch(
        name = "Show sensitive media (disabled by SafeX)",
        description = "Disabled in the SafeX bundle so X's native sensitive-media protections can never be weakened.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_X)
        execute {
            // Intentionally empty.
        }
    }
