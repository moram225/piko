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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val SAFEX =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;"
private const val SAFEX_LIST =
    "Lapp/morphe/extension/twitter/safex/SafeXListFilter;"
private const val SAFEX_SEARCH =
    "Lapp/morphe/extension/twitter/safex/SafeXSearchContext;"

/**
 * X 12.7.1's central modern URT repository. Fresh GraphQL items are mapped to
 * UrtTimelineItem objects and emitted from this suspend method.
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

/** X 12.7.1's central database/cache URT flow collector. */
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
 * Exact X 12.7.1 search repository constructor. p1 is rawQuery and field `a`
 * is the generic com.x.repositories.urt.g instance used by the search timeline.
 */
private object SafeXSearchRepositoryConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/x/repositories/search/l0;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Lcom/x/models/search/i;",
        "Lcom/x/models/search/SearchType;",
        "Lcom/x/models/search/AdvancedSearchFilters;",
        "Lcom/x/clock/c;",
        "Lcom/x/repositories/urt/g\$a;",
        "Lcom/x/featureswitches/f0;",
    ),
)

/**
 * X's legacy Search safety model is still used by the search settings/API.
 * Field `a` serializes as `optInFiltering`; field `b` is `optInBlocking`.
 * SafeX forces filtering on but does not alter the unrelated blocking setting.
 */
private object SafeXSearchSafetyConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/model/search/c;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Lcom/twitter/model/search/c\$a;"),
)

/** Native X sensitive-media display backstop. */
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
        description = "Forces X Search safety on and removes sensitive/learned NSFW posts from fresh and cached URT lists before rendering.",
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

            // Register Search's generic URT repository together with the raw
            // query. The runtime stores this in a WeakHashMap, so non-search URT
            // repositories remain context-free and destroyed searches are not
            // kept alive.
            SafeXSearchRepositoryConstructorFingerprint.method.apply {
                val returnIndex = instructions.last { it.opcode == Opcode.RETURN_VOID }.location.index
                addInstructions(
                    returnIndex,
                    """
                    iget-object v0, p0, Lcom/x/repositories/search/l0;->a:Lcom/x/repositories/urt/g;
                    invoke-static {v0, p1}, $SAFEX_SEARCH->register(Ljava/lang/Object;Ljava/lang/String;)V
                    """.trimIndent(),
                )
            }

            // X Search has an independent safe-search preference. In this exact
            // build c.a is optInFiltering and c.b is optInBlocking. Force only
            // filtering to true after X initializes the object.
            SafeXSearchSafetyConstructorFingerprint.method.apply {
                val returnIndex = instructions.last { it.opcode == Opcode.RETURN_VOID }.location.index
                addInstructions(
                    returnIndex,
                    """
                    const/4 v0, 0x1
                    iput-boolean v0, p0, Lcom/twitter/model/search/c;->a:Z
                    """.trimIndent(),
                )
            }

            // Fresh network/model path. Pass p0 (the generic URT repository) so
            // the extension can resolve whether this list belongs to Search.
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
                    "SafeX: could not find fresh URT result list in X 12.7.1"
                }

                val listRegister =
                    getInstruction<TwoRegisterInstruction>(freshIndex).registerA
                addInstructions(
                    freshIndex + 1,
                    """
                    invoke-static {p0, v$listRegister}, $SAFEX_LIST->filterNetworkItems(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                    """.trimIndent(),
                )
            }

            // Database/cache hydration path. In this instance method p0 is the
            // collector and p1 is exactly the Object that was just CHECK_CAST to
            // java.util.List. These parameters are contiguous. Using /range is
            // required because this coroutine has enough local registers that
            // p0 cannot be encoded by the normal 35c invoke form on-device.
            SafeXCachedUrtListFingerprint.method.apply {
                val cachedIndex = instructions.indexOfFirst { instruction ->
                    instruction.opcode == Opcode.CHECK_CAST &&
                        (instruction as? ReferenceInstruction)?.reference.toString() == "Ljava/util/List;"
                }
                check(cachedIndex >= 0) {
                    "SafeX: could not find cached URT List cast in X 12.7.1"
                }

                addInstructions(
                    cachedIndex + 1,
                    """
                    invoke-static/range {p0 .. p1}, $SAFEX_LIST->filterCachedItems(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                    """.trimIndent(),
                )
            }

            // General X fallback: never configure a different surface to freely
            // display sensitive media if it escapes the URT list hooks.
            SafeXAccountSensitiveMediaFingerprint.method.addInstructions(
                0,
                """
                sget-object p0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object p0
                """.trimIndent(),
            )

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
