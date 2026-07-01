package app.morphe.patches.youtube.hardlock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/hardlock/PermanentLockManager;"

/**
 * Registers the settings save interceptor that freezes locked settings in their
 * more restrictive direction, by hooking the same "Application creation"
 * fingerprint used to set the extension context.
 */
@Suppress("unused")
val permanentLockPatch = bytecodePatch(
    "HardLock: Permanent lock",
    "Freezes Shorts, home feed, and autoplay-related settings so they can't be turned back off.",
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
