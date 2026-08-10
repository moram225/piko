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
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SAFEX =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;"
private const val SAFEX_LIST =
    "Lapp/morphe/extension/twitter/safex/SafeXListFilter;"

/**
 * X 12.7.1's central modern URT repository. Fresh GraphQL items are mapped to
 * UrtTimelineItem objects and the `z$a.b` ArrayList is emitted to the timeline
 * flow from this suspend method.
 *
 * Filtering here is materially earlier than Compose rendering: removed posts
 * never enter the emitted list.
 */
private object SafeXFreshUrtListFingerprint : Fingerprint(
    definingClass = "Lcom/x/repositories/urt/g;",
    name = "b",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lcom/x/android/main/fragment/he;",
        "Z",
        "Lcom/x/models/timelines/items/UrtTimelineCursor;",
        "Lcom/x/models/timelines/a;",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
    ),
)

/**
 * X 12.7.1's central database/cache URT flow collector. The incoming Object is
 * cast to List at the beginning of emit() before filtering, module processing,
 * brand-safety spacing and downstream UI work.
 *
 * Hooking this path closes the major hole left by network-only filtering: posts
 * restored from X's local database/cache are filtered too.
 */
private object SafeXCachedUrtListFingerprint : Fingerprint(
    definingClass = "Lcom/x/repositories/urt/e\$b\$a;",
    name = "emit",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/lang/Object;",
        "Lkotlin/coroutines/Continuation;",
    ),
)

/**
 * Native X safety backstop. SafeX removes posts at the model-list level, while
 * this ensures X itself is never configured to freely display sensitive media
 * on a surface that does not use the generic URT repository.
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
        description = "Removes X-sensitive posts from fresh and cached modern URT lists before UI rendering and learns local text/hashtag indicators.",
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

            // -----------------------------------------------------------------
            // Fresh network/model path.
            // Exact X 12.7.1 target in g.b():
            //   iget-object vX, ..., Lcom/x/repositories/urt/z$a;->b:ArrayList
            //   ...
            //   flow.emit(vX, continuation)
            // Replace vX with SafeX's filtered List immediately after the load.
            // -----------------------------------------------------------------
            SafeXFreshUrtListFingerprint.method.apply {
                val freshIndex = instructions.indexOfLast { instruction ->
                    if (instruction.opcode != Opcode.IGET_OBJECT) return@indexOfLast false
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    reference?.definingClass == "Lcom/x/repositories/urt/z\$a;" &&
                        reference.name == "b" &&
                        reference.type == "Ljava/util/ArrayList;"
                }
                check(freshIndex >= 0) {
                    "SafeX: could not find fresh URT z$a.b list in X 12.7.1"
                }

                val listRegister =
                    getInstruction<TwoRegisterInstruction>(freshIndex).registerA
                addInstructions(
                    freshIndex + 1,
                    """
                    invoke-static {v$listRegister}, $SAFEX_LIST->filterNetworkItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                    """.trimIndent(),
                )
            }

            // -----------------------------------------------------------------
            // Database/cache hydration path.
            // Exact X 12.7.1 target in e$b$a.emit(): the first check-cast List is
            // the list received from the DB flow. Filter that same register before
            // X iterates it or performs module/spacing work.
            // -----------------------------------------------------------------
            SafeXCachedUrtListFingerprint.method.apply {
                val cachedIndex = instructions.indexOfFirst { instruction ->
                    if (instruction.opcode != Opcode.CHECK_CAST) return@indexOfFirst false
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? TypeReference
                    reference?.type == "Ljava/util/List;"
                }
                check(cachedIndex >= 0) {
                    "SafeX: could not find cached URT List cast in X 12.7.1"
                }

                val listRegister =
                    getInstruction<OneRegisterInstruction>(cachedIndex).registerA
                addInstructions(
                    cachedIndex + 1,
                    """
                    invoke-static {v$listRegister}, $SAFEX_LIST->filterCachedItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                    """.trimIndent(),
                )
            }

            // Native X fallback. If a completely different surface escapes both
            // generic URT repository hooks, X should still keep sensitive media
            // hidden rather than becoming more permissive.
            SafeXAccountSensitiveMediaFingerprint.method.addInstructions(
                0,
                """
                sget-object p0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object p0
                """.trimIndent(),
            )

            // Three-dot menu: reuse X's existing MarkTweetPossiblySensitive slot,
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
