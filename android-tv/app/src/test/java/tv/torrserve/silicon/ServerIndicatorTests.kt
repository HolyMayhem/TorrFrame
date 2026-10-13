package tv.torrserve.silicon

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.ui.components.*

class ServerIndicatorTests {
    @Test fun localProcessTransitionsOverrideStaleSuccessfulProbe() {
        val connected = ConnectionState(ConnectionStatus.Connected)
        assertEquals(ServerIndicator.Offline, serverIndicator(true, ServerState.Stopped, connected))
        assertEquals(ServerIndicator.Busy, serverIndicator(true, ServerState.Starting, connected))
        assertEquals(ServerIndicator.Busy, serverIndicator(true, ServerState.Stopping, connected))
        assertEquals(ServerIndicator.Error, serverIndicator(true, ServerState.Error("failed"), connected))
        assertEquals(ServerIndicator.Online, serverIndicator(true, ServerState.Running("test", 1), connected))
    }
    @Test fun externalServerUsesConnectionRatherThanLocalProcess() {
        for ((status, indicator) in listOf(ConnectionStatus.Connected to ServerIndicator.Online,
            ConnectionStatus.Connecting to ServerIndicator.Busy, ConnectionStatus.Error to ServerIndicator.Error,
            ConnectionStatus.Unavailable to ServerIndicator.Offline)) {
            assertEquals(indicator, serverIndicator(false, ServerState.Stopped, ConnectionState(status)))
        }
    }
}
