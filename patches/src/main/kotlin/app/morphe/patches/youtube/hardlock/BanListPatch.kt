package app.morphe.patches.youtube.hardlock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/hardlock/BanListManager;"

/**
 * Runs an early ban list integrity check on app launch, by hooking the same
 * "Application creation" fingerprint used to set the extension context.
 */
@Suppress("unused")
val banListPatch = bytecodePatch(
    "HardLock: Ban list",
    "Enforces the HardLock irreversible banned terms/channels list.",
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
