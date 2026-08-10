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
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

private const val SAFEX =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;"
private const val SAFEX_RESPONSE =
    "Lapp/morphe/extension/twitter/safex/SafeXResponseFilter;"
private const val JACKSON_CLASS = "/fasterxml/jackson/core/"

/**
 * Same stable Jackson InputStream path Piko uses for its server-response logger.
 * SafeX modifies the JSON stream before X's model deserializers see it.
 */
private object SafeXInputStreamFingerprint : Fingerprint(
    definingClass = JACKSON_CLASS,
    parameters = listOf("Ljava/io/InputStream"),
    custom = { methodDef, _ ->
        methodDef.returnType.contains(JACKSON_CLASS)
    },
)

/**
 * X 12.7.1 modern UI setting. SafeX always reports "do not display sensitive
 * media" locally. This is a backstop; the primary mechanism removes flagged
 * timeline entries from the GraphQL response itself.
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
        description = "Removes X-sensitive posts from GraphQL before rendering and learns local text/hashtag indicators.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            tweetEntityPatch,
            shareMenuButtonOnClickHook,
        )

        execute {
            // Enable early during normal Piko startup.
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $SAFEX->enable()V",
            )

            // The response filter calls enable() again before its first use so
            // cached/startup ordering cannot leave SafeX inactive.
            SafeXInputStreamFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p1}, $SAFEX_RESPONSE->filterInputStream(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
                """.trimIndent(),
            )

            // Native X safety backstop: never tell the app that sensitive media
            // is allowed to display while SafeX is installed.
            SafeXAccountSensitiveMediaFingerprint.method.addInstructions(
                0,
                """
                sget-object p0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object p0
                """.trimIndent(),
            )

            // Three-dot menu: reuse X's existing action slot but relabel it.
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
