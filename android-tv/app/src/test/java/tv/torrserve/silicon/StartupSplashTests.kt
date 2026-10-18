package tv.torrserve.silicon

import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.features.startup.*

class StartupSplashTests {
    @Test fun readyUiStartsFadingImmediatelyAfterVideoWithoutBlackPause() {
        var time = 100L
        val splash = StartupSplashState { time }
        splash.begin(); assertTrue(splash.claimVideo())
        splash.fadeWhenReady(true); assertEquals(StartupPhase.Video, splash.state.value.phase)
        time += 2_667; splash.videoEnded()
        splash.fadeWhenReady(true); assertEquals(StartupPhase.Fading, splash.state.value.phase)
        assertEquals(0f, splash.fadeProgress(), 0f)
        time += 600; assertEquals(.5f, splash.fadeProgress(), .001f)
        time += 600; assertEquals(1f, splash.fadeProgress(), 0f)
        splash.finish(); assertFalse(splash.visible)
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
        time = 1_500; splash.fadeWhenReady(true); time = 2_100
        splash.stopped(changingConfiguration = true)
        assertEquals(.5f, splash.fadeProgress(), .001f)
    }
    @Test fun backgroundingAndBackFinishTheIntroInsteadOfReplayingItOnReturn() {
        for (phase in listOf(StartupPhase.Pending, StartupPhase.Video, StartupPhase.Black, StartupPhase.Fading)) {
            var time = 0L
            val splash = StartupSplashState { time }
            if (phase != StartupPhase.Pending) splash.begin()
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
