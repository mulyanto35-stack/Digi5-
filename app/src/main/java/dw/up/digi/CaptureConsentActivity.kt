package dw.up.digi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dw.up.digi.accessibility.DigiWorldAccessibilityService
import dw.up.digi.capture.CaptureFrameAnalyzer
import dw.up.digi.capture.CaptureSessionState
import dw.up.digi.capture.ScreenCaptureService
import dw.up.digi.dungeon.DungeonFrameAnalyzer
import dw.up.digi.feed.FeedFrameAnalyzer
import dw.up.digi.feed.StageFailedFrameAnalyzer
import dw.up.digi.network.NetworkDefenseFrameAnalyzer
import dw.up.digi.purchase.RewardPurchaseFrameAnalyzer
import dw.up.digi.strategy.AutoMoveController
import dw.up.digi.strategy.AutomationState
import dw.up.digi.strategy.DwsNavigationSettings

/** Requests MediaProjection without bringing the full settings UI in front of the game. */
class CaptureConsentActivity : ComponentActivity() {
    private companion object {
        const val GAME_PACKAGE = "com.bandainamcoent.dgup_ww"
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            applyRuntimeSettings()
            AutomationState.stop()
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            ScreenCaptureService.setAutomation(this, true)
            DigiWorldAccessibilityService.instance?.setOverlayEnabled(AutomationState.overlayEnabled)
            returnToGame()
        } else {
            Toast.makeText(this, getString(R.string.status_capture_denied), Toast.LENGTH_SHORT).show()
        }
        finishWithoutAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CaptureSessionState.snapshot(AutomationState.enabled).captureActive) {
            applyRuntimeSettings()
            ScreenCaptureService.setAutomation(this, true)
            finishWithoutAnimation()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        consent.launch(manager.createScreenCaptureIntent())
    }

    private fun applyRuntimeSettings() {
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val neverLeft = settings.getBoolean("dws_never_left", false)
        AutomationState.overlayEnabled = settings.getBoolean("grid_enabled", true)
        AutomationState.autoPurchaseEnabled = settings.getBoolean("auto_purchase", true)
        AutomationState.autoDungeonEnabled = settings.getBoolean("auto_dungeon", true)
        AutomationState.autoNetworkDefenseEnabled = settings.getBoolean("auto_network_defense", false)
        AutomationState.autoFeedEnabled = settings.getBoolean("auto_feed", false)
        AutomationState.forceLegacyCaptureMetrics = settings.getBoolean("legacy_capture", true)
        AutomationState.summonTouchCorrection = settings.getBoolean("summon_touch_correction", false)
        AutomationState.dwsNavigationSettings = DwsNavigationSettings(
            allowLeft = !neverLeft,
            forceForwardAttack = neverLeft,
            dashSpamUntilZero = settings.getBoolean("dws_dash_spam", false),
            collectOnlyEnergy = settings.getBoolean("dws_only_energy", false),
            betterEnergyCollect = true,
            blindStageFailedTap = true,
        )
        settings.edit().putBoolean("dws_blind_stage_tap", true).apply()
        RewardPurchaseFrameAnalyzer.reset()
        DungeonFrameAnalyzer.reset()
        NetworkDefenseFrameAnalyzer.reset()
        FeedFrameAnalyzer.reset()
        StageFailedFrameAnalyzer.reset()
        CaptureFrameAnalyzer.resetCalibration()
        AutoMoveController.reset()
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun returnToGame() {
        packageManager.getLaunchIntentForPackage(GAME_PACKAGE)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launch)
        }
    }
}