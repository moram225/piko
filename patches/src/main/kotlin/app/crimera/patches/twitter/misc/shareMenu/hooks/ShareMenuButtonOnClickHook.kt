/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * SafeX modification: routes MarkTweetPossiblySensitive to local SafeX
 * training, then remaps it to X's IDontLikeThisTweet action so X removes the
 * post immediately using its native timeline feedback path.
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.shareMenu.hooks

import app.crimera.patches.twitter.entity.decoder.TWEET_ITEM_CLASS_NAME
import app.crimera.patches.twitter.entity.decoder.decoderEntity
import app.crimera.patches.twitter.misc.shareMenu.fingerprints.ActionEnumsFingerprint
import app.crimera.patches.twitter.misc.shareMenu.fingerprints.ShareMenuButtonFuncCallFingerprint
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.NATIVE_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val EXTENSION_CLASS_NAME = "${NATIVE_DESCRIPTOR}/ButtonPressHandler;->"
private const val SAFEX_DESCRIPTOR =
    "Lapp/morphe/extension/twitter/safex/SafeXRuntime;->"

@Suppress("unused")
val shareMenuButtonOnClickHook =
    bytecodePatch(
        description = "Hook required to configure share menu onclick action",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(decoderEntity)

        execute {
            ShareMenuButtonFuncCallFingerprint.apply {
                val isStaticMethod = AccessFlags.STATIC.isSet(method.accessFlags)
                val actionEnumClass = ActionEnumsFingerprint.classDef.toString()

                method.apply {
                    var tweetObjectParameter =
                        parameters.indexOfFirst { it.type == TWEET_ITEM_CLASS_NAME }

                    if (!isStaticMethod) {
                        tweetObjectParameter += 1
                    }

                    val contextIfEqzIndex = indexOfFirstInstruction(Opcode.IF_EQZ)
                    val contextRegister =
                        getInstruction(contextIfEqzIndex).registersUsed[0]

                    val buttonEnumPressCheckIndex =
                        indexOfFirstInstruction(contextIfEqzIndex, Opcode.IF_NE)
                    val buttonPressedRegister =
                        getInstruction(buttonEnumPressCheckIndex).registersUsed[0]

                    val nextInstructionIndex = contextIfEqzIndex + 1
                    val nextInstruction = getInstruction(nextInstructionIndex)
                    val freeRegister = nextInstruction.registersUsed[0]

                    addInstructionsWithLabels(
                        nextInstructionIndex,
                        """
                        invoke-static {v$buttonPressedRegister}, ${SAFEX_DESCRIPTOR}isManualAction(Ljava/lang/Object;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :piko_actions

                        move-object/from16 v$freeRegister, p$tweetObjectParameter
                        invoke-static {v$freeRegister}, ${SAFEX_DESCRIPTOR}markManualNsfw(Ljava/lang/Object;)V

                        sget-object v$buttonPressedRegister, $actionEnumClass->IDontLikeThisTweet:$actionEnumClass
                        goto :native_x

                        :piko_actions
                        invoke-static {v$buttonPressedRegister}, ${EXTENSION_CLASS_NAME}isButtonPressed(Ljava/lang/Object;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :native_x

                        move-object/from16 v$freeRegister, p$tweetObjectParameter
                        invoke-static {v$contextRegister,v$buttonPressedRegister,v$freeRegister}, ${EXTENSION_CLASS_NAME}buttonPressAction(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;)V
                        return-void
                        """.trimIndent(),
                        ExternalLabel("native_x", nextInstruction),
                    )
                }
            }
        }
    }
