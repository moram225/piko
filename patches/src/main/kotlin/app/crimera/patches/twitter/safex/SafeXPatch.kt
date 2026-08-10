package app.crimera.patches.twitter.safex

import app.crimera.patches.twitter.ads.timelineEntryHook.timelineEntryHookPatch
import app.crimera.patches.twitter.entity.tweet.TweetObjectFingerprint
import app.crimera.patches.twitter.entity.tweet.tweetEntityPatch
import app.crimera.patches.twitter.misc.settings.SettingsStatusLoadFingerprint
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.fingerprints.ActionEnumsFingerprint
import app.crimera.patches.twitter.misc.shareMenu.hooks.ShareMenuButtonAddHook
import app.crimera.patches.twitter.misc.shareMenu.hooks.setButtonText
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

private const val SAFEX =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;"

private object SafeXSensitiveWarningFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/core/JsonSensitiveMediaWarning\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object",
)

private object SafeXTimelineEntryFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/timeline/urt/JsonTimelineEntry\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object",
)

private object SafeXTimelineModuleItemFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/json/timeline/urt/JsonTimelineModuleItem\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object",
)

@Suppress("unused")
val safeXPatch =
    bytecodePatch(
        name = "SafeX: remove NSFW posts",
        description = "Drops X-sensitive posts before rendering and learns local text/hashtag indicators.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            tweetEntityPatch,
            timelineEntryHookPatch,
        )

        execute {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $SAFEX->enable()V",
            )

            listOf(
                SafeXTimelineEntryFingerprint.method,
                SafeXTimelineModuleItemFingerprint.method,
            ).forEach { method ->
                method.addInstruction(
                    0,
                    "invoke-static {}, $SAFEX->beginEntry()V",
                )

                val returnIndex =
                    method.instructions.last { it.opcode == Opcode.RETURN_OBJECT }.location.index

                method.addInstructions(
                    returnIndex,
                    """
                    invoke-static {p1}, $SAFEX->finishEntry(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    """.trimIndent(),
                )
            }

            SafeXSensitiveWarningFingerprint.method.apply {
                val returnIndex =
                    instructions.last { it.opcode == Opcode.RETURN_OBJECT }.location.index
                addInstructions(
                    returnIndex,
                    "invoke-static {p1}, $SAFEX->observeSensitiveWarning(Ljava/lang/Object;)V",
                )
            }

            TweetObjectFingerprint.classDef.methods
                .filter { it.name == "<init>" }
                .forEach { constructor ->
                    val returnIndex =
                        constructor.instructions.last { it.opcode == Opcode.RETURN_VOID }.location.index
                    constructor.addInstructions(
                        returnIndex,
                        "invoke-static {p0}, $SAFEX->observeTweet(Ljava/lang/Object;)V",
                    )
                }

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
