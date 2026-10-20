package tv.torrframe

import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.features.startup.*

class StartupSplashTests {
    @Test fun readyUiStartsFadingImmediatelyAfterVideoWithoutBlackPause() {
        var time = 100L
        val splash = StartupSplashState { time }
        splash.begin(); assertTrue(splash.claimVideo())
        splash.fadeWhenReady(true); assertEquals(StartupPhase.Video, splash.state.value.phase)
        time += 2_667; splash.videoEnded()
        splash.fadeWhenReady(true); assertEquals(StartupPhase.Fading, splash.state.value.phase)
        assertEquals(0f, splash.fadeProgress(), 0f)
        time += 250; assertEquals(.5f, splash.fadeProgress(), .001f)
        time += 250; assertEquals(1f, splash.fadeProgress(), 0f)
        splash.finish(); assertFalse(splash.visible)
    }
    @Test fun loadingHintUsesFiveSecondsFromStartupAndResetsForEachPreview() {
        var time = 100L
        val splash = StartupSplashState { time }
        splash.begin()
        time += 2_000; splash.checkReadinessAt1900ms(); splash.freezeAt2000ms()
        assertEquals(3_000L, splash.loadingHintRemainingMs())
        time += 2_999; assertEquals(1L, splash.loadingHintRemainingMs())
        time += 1; assertEquals(0L, splash.loadingHintRemainingMs())
        splash.setUiReady(true); assertEquals(StartupPhase.Video, splash.state.value.phase)
        splash.finish(); time += 10_000; splash.restartForPreview(7_000); splash.begin()
        assertEquals(5_000L, splash.loadingHintRemainingMs())
    }
    @Test fun slowUiHoldsAtTwoSecondsAndResumesWithoutClaimingAnotherVideo() {
        var time = 0L
        val splash = StartupSplashState { time }
        splash.begin(); assertTrue(splash.claimVideo())
        time = 1_900; splash.checkReadinessAt1900ms()
        time = 2_000; assertTrue(splash.freezeAt2000ms())
        assertEquals(StartupPhase.Frozen, splash.state.value.phase)
        time = 30_000; assertEquals(StartupPhase.Frozen, splash.state.value.phase)
        splash.setUiReady(true)
        assertEquals(StartupPhase.Video, splash.state.value.phase)
        assertEquals(6_000L, splash.videoRemainingMs())
        assertFalse(splash.claimVideo())
        splash.videoEnded(); splash.fadeWhenReady(true)
        time += 500; assertEquals(1f, splash.fadeProgress(), 0f)
    }
    @Test fun readyUiAndReadinessArrivingBetweenCheckAndHoldDoNotPauseTheClip() {
        for (early in listOf(true, false)) {
            val splash = StartupSplashState { 0 }
            splash.begin(); splash.setUiReady(early); splash.checkReadinessAt1900ms()
            splash.setUiReady(true)
            assertFalse(splash.freezeAt2000ms())
            assertEquals(StartupPhase.Video, splash.state.value.phase)
        }
    }
    @Test fun debugPreviewResetsOnlyIntroTimingAndNeverPersistsTheSlowMode() {
        var time = 0L
        val splash = StartupSplashState { time }
        splash.begin(); splash.claimVideo(); splash.finish()
        time = 100; splash.restartForPreview(7_000); splash.begin()
        assertTrue(splash.claimVideo()); assertEquals(7_000L, splash.readyDelayRemainingMs())
        time = 4_100; assertEquals(3_000L, splash.readyDelayRemainingMs())
        time = 10_100; assertEquals(0L, splash.readyDelayRemainingMs())
        splash.finish(); splash.restartForPreview(0); splash.begin()
        assertEquals(0L, splash.readyDelayRemainingMs())
    }
    @Test fun failedVideoOrRecreationDuringFreezeCannotStrandTheStill() {
        for (failed in listOf(true, false)) {
            val splash = StartupSplashState { 0 }
            splash.begin(); splash.claimVideo(); splash.checkReadinessAt1900ms(); splash.freezeAt2000ms()
            if (failed) splash.videoEnded(true) else splash.stopped(changingConfiguration = true)
            assertEquals(StartupPhase.Black, splash.state.value.phase)
            splash.fadeWhenReady(true); assertEquals(StartupPhase.Fading, splash.state.value.phase)
            assertFalse(splash.claimVideo())
        }
    }
    @Test fun onlyOnePlaybackCanBeClaimedForTheEntireProcess() {
        val splash = StartupSplashState { 0 }
        assertFalse(splash.claimVideo())
        splash.begin(); assertTrue(splash.claimVideo()); assertFalse(splash.claimVideo())
        splash.videoEnded(); splash.begin(); assertFalse(splash.claimVideo())
        splash.finish(); splash.begin(); assertEquals(StartupPhase.Finished, splash.state.value.phase)
        assertFalse(splash.claimVideo())
        val newProcess = StartupSplashState { 0 }; newProcess.begin(); assertTrue(newProcess.claimVideo())
    }
    @Test fun recreationNeverRewindsVideoOrRestartsAnExistingReadinessWaitOrFade() {
        var time = 0L
        val splash = StartupSplashState { time }
        splash.begin(); splash.claimVideo(); time = 500
        splash.stopped(changingConfiguration = true)
        assertEquals(StartupPhase.Black, splash.state.value.phase); assertFalse(splash.claimVideo())
        time = 1_000; splash.fadeWhenReady(false); splash.stopped(changingConfiguration = true)
        assertEquals(StartupPhase.Black, splash.state.value.phase)
        assertEquals(500L, splash.state.value.phaseStartedAt)
        time = 1_500; splash.fadeWhenReady(true); time = 1_750
        splash.stopped(changingConfiguration = true)
        assertEquals(.5f, splash.fadeProgress(), .001f)
    }
    @Test fun backgroundingAndBackFinishTheIntroInsteadOfReplayingItOnReturn() {
        for (phase in listOf(StartupPhase.Pending, StartupPhase.Video, StartupPhase.Frozen, StartupPhase.Black, StartupPhase.Fading)) {
            var time = 0L
            val splash = StartupSplashState { time }
            if (phase != StartupPhase.Pending) splash.begin()
            if (phase == StartupPhase.Frozen) { splash.checkReadinessAt1900ms(); splash.freezeAt2000ms() }
            if (phase in listOf(StartupPhase.Black, StartupPhase.Fading)) splash.videoEnded()
            if (phase == StartupPhase.Fading) splash.fadeWhenReady(true)
            splash.stopped(changingConfiguration = false); splash.begin()
            assertEquals(StartupPhase.Finished, splash.state.value.phase); assertFalse(splash.claimVideo())
            assertEquals(1f, splash.fadeProgress(), 0f)
        }
    }
    @Test fun failedVideoAndLateDuplicateCallbacksCannotLeaveTheCoverStuck() {
        var time = 0L
        val splash = StartupSplashState { time }
        splash.begin(); splash.videoEnded(failed = true)
        assertTrue(splash.state.value.videoFailed)
        time = 500; splash.videoEnded(); splash.fadeWhenReady(false)
        assertEquals(StartupPhase.Black, splash.state.value.phase)
        assertEquals(0L, splash.state.value.phaseStartedAt)
        time = 1_000; splash.fadeWhenReady(true); splash.finish(); splash.videoEnded(true)
        assertFalse(splash.visible)
    }
    @Test fun readinessChangesDoNotExtendTheDecoderWatchdog() {
        var time = 0L
        val splash = StartupSplashState { time }
        splash.begin(); time = 5_000
        assertEquals(1_000, splash.videoRemainingMs())
        splash.fadeWhenReady(true)
        assertEquals(1_000, splash.videoRemainingMs())
        time = 8_000; assertEquals(0, splash.videoRemainingMs())
    }
}
