package app.crimera.patches.twitter.safex

import app.crimera.patches.twitter.entity.tweet.tweetEntityPatch
import app.crimera.patches.twitter.misc.settings.SettingsStatusLoadFingerprint
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.fingerprints.ActionEnumsFingerprint
import app.crimera.patches.twitter.misc.shareMenu.hooks.ShareMenuButtonAddHook
import app.crimera.patches.twitter.misc.shareMenu.hooks.setButtonText
import app.crimera.patches.twitter.misc.shareMenu.hooks.shareMenuButtonOnClickHook
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

private const val SAFEX =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;"
private const val SAFEX_MODERN =
    "Lapp/morphe/extension/twitter/safex/SafeXModern;"

/**
 * Modern X 12.7.1 top-level URT LazyList item content lambda.
 * Its field `a` is the final UrtTimelineItem that X is about to render.
 */
private object SafeXTopLevelUrtRendererFingerprint : Fingerprint(
    definingClass = "Lcom/x/urt/ui/r0;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
    ),
)

/**
 * Modern X 12.7.1 module-item content lambda. Module children take a separate
 * rendering path from top-level items, so both paths must be guarded.
 */
private object SafeXModuleUrtRendererFingerprint : Fingerprint(
    definingClass = "Lcom/x/urt/ui/module/h;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
    ),
)

/**
 * Native X setting backstop. The actual post removal happens at the modern URT
 * renderer, but this ensures X itself is never configured to freely display
 * sensitive media if another surface escapes the renderer hooks.
 */
private object SafeXAccountSensitiveMediaFingerprint : Fingerprint(
    definingClass = "Lcom/x/models/AccountSettings;",
    name = "getDisplaySensitiveMedia",
    returnType = "Ljava/lang/Boolean;",
    parameters = emptyList(),
)

@Suppress("unused")
val safeXPatch =
    bytecodePatch(
        name = "SafeX: remove NSFW posts",
        description = "Removes X-sensitive modern URT posts immediately before rendering and learns local text/hashtag indicators.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            tweetEntityPatch,
            shareMenuButtonOnClickHook,
        )

        execute {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $SAFEX->enable()V",
            )

            // Network-only filtering is deliberately NOT used in v0.4. X can
            // hydrate these same modern models from caches/local storage. The
            // final renderer is the convergence point for those data sources.
            SafeXTopLevelUrtRendererFingerprint.method.apply {
                val originalFirst = instructions.first()
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, Lcom/x/urt/ui/r0;->a:Lcom/x/models/timelines/items/UrtTimelineItem;
                    invoke-static {v0}, $SAFEX_MODERN->shouldRemoveTimelineItem(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :safex_continue
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("safex_continue", originalFirst),
                )
            }

            SafeXModuleUrtRendererFingerprint.method.apply {
                val originalFirst = instructions.first()
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, Lcom/x/urt/ui/module/h;->a:Lcom/x/models/timelines/items/UrtTimelineItem;
                    invoke-static {v0}, $SAFEX_MODERN->shouldRemoveTimelineItem(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :safex_continue
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("safex_continue", originalFirst),
                )
            }

            SafeXAccountSensitiveMediaFingerprint.method.addInstructions(
                0,
                """
                sget-object p0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object p0
                """.trimIndent(),
            )

            // Three-dot menu: reuse X's native MarkTweetPossiblySensitive slot,
            // relabel it, train locally, then the existing click hook remaps it
            // to X's native IDontLikeThisTweet removal path.
            setButtonText(
                "MarkTweetPossiblySensitive",
                "safex_mark_nsfw",
            )

            val actionEnum = ActionEnumsFingerprint.classDef.toString()
            val actionReference =
                "$actionEnum->MarkTweetPossiblySensitive:$actionEnum"

            ShareMenuButtonAddHook.method.apply {
                val lastParamIndex = parameters.lastIndex
                val addToCollection =
                    instructions.last { it.opcode == Opcode.INVOKE_VIRTUAL } as Instruction35c

                addInstructions(
                    0,
                    """
                    sget-object v0, $actionReference
                    invoke-virtual {p$lastParamIndex, v0}, ${addToCollection.reference}
                    """.trimIndent(),
                )
            }
        }
    }
