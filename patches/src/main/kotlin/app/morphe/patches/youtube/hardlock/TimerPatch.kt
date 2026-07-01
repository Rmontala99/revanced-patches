package app.morphe.patches.youtube.hardlock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/hardlock/TimerManager;"

/**
 * Starts the mandatory session timer as soon as the app is launched, by hooking
 * the same "Application creation" fingerprint used to set the extension context.
 */
@Suppress("unused")
val timerPatch = bytecodePatch(
    "HardLock: Timer",
    "Starts the mandatory HardLock session timer when the app launches.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(sharedExtensionPatch)

    execute {
        // Index 1: right after the extension context is set at index 0.
        applicationInitHook.fingerprint.method.addInstruction(
            1,
            "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->initialize(Landroid/content/Context;)V",
        )
    }
}
