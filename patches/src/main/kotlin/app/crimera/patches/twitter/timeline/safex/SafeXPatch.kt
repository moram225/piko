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
private const val SAFEX_SETTINGS_CLASS = "$PATCHES_DESCRIPTOR/SafeXSettings;"
private const val SENSITIVE_SETTINGS_MODEL = "Lcom/twitter/sensitivemedia/model/b;"
private const val SEARCH_MODEL = "Lcom/twitter/model/search/c;"

/** X's sensitive-media settings repository (impl: request/c, a() = get). */
private object SensitiveSettingsGetFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/sensitivemedia/request/c;",
    name = "a",
    returnType = "Lio/reactivex/internal/operators/single/x;",
)

/** b(model) = write the sensitive-media setting. */
private object SensitiveSettingsSetFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/sensitivemedia/request/c;",
    name = "b",
    returnType = "Lio/reactivex/internal/operators/single/x;",
)

/** Read path for search settings: h(response) -> model (bridge return included). */
private object SearchSettingsReadFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/android/search/implementation/settings/repository/a;",
    name = "h",
)

/** Write path for search settings. */
private object SearchSettingsWriteFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/android/search/implementation/settings/repository/c;",
    name = "f",
    returnType = "Lcom/twitter/async/http/a;",
)

/** Response parser of the search-settings update (Boolean result). */
private object SearchSettingsResultFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/android/search/implementation/settings/repository/c;",
    name = "h",
)

/**
 * The actual update request: POST /1.1/strato/column/User/<id>/search/searchSafety.
 * Every UI path (settings screen, 3-dot toggle) must build this object, so
 * forcing its model covers all of them.
 */
private object SearchSafetyRequestFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/api/legacy/request/search/g;",
    name = "<init>",
)

/**
 * Same idea for the sensitive-media update request; covers any future UI entry
 * point that bypasses the settings screen.
 */
private object SensitiveSettingsRequestFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/sensitivemedia/request/a;",
    name = "<init>",
)

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

            // 4. Lock X's own "hide sensitive content" setting to Drop x3.
            SensitiveSettingsGetFingerprint.method.addInstructions(
                0,
                "invoke-static { p0 }, $SAFEX_SETTINGS_CLASS->enforce(Ljava/lang/Object;)V",
            )
            SensitiveSettingsSetFingerprint.method.addInstructions(
                0,
                """
                invoke-static { p1 }, $SAFEX_SETTINGS_CLASS->forceDrop($SENSITIVE_SETTINGS_MODEL)$SENSITIVE_SETTINGS_MODEL
                move-result-object p1
                """.trimIndent(),
            )

            // 5. Lock Search settings to "Hide sensitive content".
            SearchSettingsWriteFingerprint.method.addInstructions(
                0,
                """
                invoke-static { p1 }, $SAFEX_SETTINGS_CLASS->forceSearchWrite(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $SEARCH_MODEL
                """.trimIndent(),
            )
            val searchResult = SearchSettingsResultFingerprint.method
            val searchResultReturn = searchResult.instructions.last { it.opcode == Opcode.RETURN_OBJECT }
            val searchResultRegister = searchResult
                .getInstruction<OneRegisterInstruction>(searchResultReturn.location.index)
                .registerA
            searchResult.addInstructions(
                searchResultReturn.location.index,
                """
                invoke-static { v$searchResultRegister }, $SAFEX_SETTINGS_CLASS->reportSearchWrite(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$searchResultRegister
                check-cast v$searchResultRegister, Ljava/lang/Boolean;
                """.trimIndent(),
            )

            // 6. Force the outgoing requests themselves, so no UI path can bypass
            //    the locks (e.g. the 3-dot toggle in search results).
            SearchSafetyRequestFingerprint.method.addInstructions(
                0,
                """
                invoke-static { p2 }, $SAFEX_SETTINGS_CLASS->forceSearchWrite(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p2
                check-cast p2, $SEARCH_MODEL
                """.trimIndent(),
            )
            SensitiveSettingsRequestFingerprint.method.addInstructions(
                0,
                """
                invoke-static { p2 }, $SAFEX_SETTINGS_CLASS->forceDrop($SENSITIVE_SETTINGS_MODEL)$SENSITIVE_SETTINGS_MODEL
                move-result-object p2
                """.trimIndent(),
            )
            val searchRead = SearchSettingsReadFingerprint.method
            val searchReadReturn = searchRead.instructions.last { it.opcode == Opcode.RETURN_OBJECT }
            val searchReadRegister = searchRead
                .getInstruction<OneRegisterInstruction>(searchReadReturn.location.index)
                .registerA
            searchRead.addInstructions(
                searchReadReturn.location.index,
                """
                invoke-static { v$searchReadRegister }, $SAFEX_SETTINGS_CLASS->forceSearchRead(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$searchReadRegister
                check-cast v$searchReadRegister, $SEARCH_MODEL
                """.trimIndent(),
            )
        }
    }
