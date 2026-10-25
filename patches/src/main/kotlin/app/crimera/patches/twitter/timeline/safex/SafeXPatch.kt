/*
 * SafeX - remove posts that X classifies as sensitive, before they render.
 *
 * Two injections, both on JSON parse paths already proven by piko's ad filter:
 *  1. entry parsers  -> beginEntry() at method start; the existing
 *     TimelineEntry.checkEntry(...) hook closes the frame and returns null.
 *  2. media parser   -> observeMedia(result) at return, marking the frame when
 *     X set possibly_sensitive or any sensitive_media_warning boolean.
 *
 * Dropping is done by returning null from the parse method (same mechanism as
 * Hide Ads), so no cell, interstitial, placeholder or blank row is created.
 */

package app.crimera.patches.twitter.timeline.safex

import app.crimera.patches.twitter.ads.timelineEntryHook.timelineEntryHookPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SAFEX_CLASS = "$PATCHES_DESCRIPTOR/SafeX;"

private object SafeXEntryParseFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/timeline/urt/JsonTimelineEntry\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object;",
)

private object SafeXModuleItemParseFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/timeline/urt/JsonTimelineModuleItem\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object;",
)

private object SafeXMediaEntityParseFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/core/JsonMediaEntity\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object;",
)

/**
 * X's age/adult gate. `media_visibility_results` is attached to a tweet when X
 * decided to restrict its media, and carries the blurred-image interstitial that
 * renders as "Content warning: Adult Content".
 */
private object SafeXMediaVisibilityParseFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/mediavisibility/JsonMediaVisibilityResults\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object;",
)

@Suppress("unused")
val safeX =
    bytecodePatch(
        name = "SafeX: remove sensitive posts",
        description = "Removes any post X classifies as sensitive before it reaches the timeline",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(timelineEntryHookPatch)

        execute {
            // 1. Open a detection frame for every timeline entry parse.
            for (fingerprint in listOf(SafeXEntryParseFingerprint, SafeXModuleItemParseFingerprint)) {
                fingerprint.method.addInstructions(
                    0,
                    """
                    invoke-static {}, $SAFEX_CLASS->beginEntry()V
                    """.trimIndent(),
                )
            }

            // 2. Mark the current frame from every parsed media entity.
            val mediaParse = SafeXMediaEntityParseFingerprint.method
            val returnObject = mediaParse.instructions.last { it.opcode == Opcode.RETURN_OBJECT }
            val returnRegister = mediaParse
                .getInstruction<OneRegisterInstruction>(returnObject.location.index)
                .registerA
            mediaParse.addInstructions(
                returnObject.location.index,
                """
                invoke-static { v$returnRegister }, $SAFEX_CLASS->observeMedia(Ljava/lang/Object;)V
                """.trimIndent(),
            )

            // 3. Mark the frame when X attaches media-visibility (age/adult) results.
            val visibilityParse = SafeXMediaVisibilityParseFingerprint.method
            val visibilityReturn = visibilityParse.instructions.last { it.opcode == Opcode.RETURN_OBJECT }
            visibilityParse.addInstructions(
                visibilityReturn.location.index,
                """
                invoke-static {}, $SAFEX_CLASS->observeVisibility()V
                """.trimIndent(),
            )
        }
    }
