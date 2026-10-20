package tv.torrframe.features.library

import tv.torrframe.core.server.*

enum class LibraryAvailability { Ready, Connecting, Stopped, Unavailable, AccessDenied, InvalidResponse }
fun libraryAvailability(local:Boolean,server:ServerState,connection:ConnectionState,simulatedOffline:Boolean=false, syncFailure:Int?=null):LibraryAvailability {
    if(simulatedOffline)return LibraryAvailability.Unavailable
    if(local)when(server) {
        ServerState.Stopped,ServerState.Stopping -> return LibraryAvailability.Stopped
        ServerState.Starting -> return LibraryAvailability.Connecting
        is ServerState.Error -> return LibraryAvailability.Unavailable
        is ServerState.Running -> Unit
    }
    return when(connection.status) {
        ConnectionStatus.Connected -> if(syncFailure==null)LibraryAvailability.Ready else failureAvailability(syncFailure)
        ConnectionStatus.Connecting -> LibraryAvailability.Connecting
        ConnectionStatus.Unavailable -> LibraryAvailability.Unavailable
        ConnectionStatus.Error -> failureAvailability(connection.failure)
    }
}

private fun failureAvailability(failure:Int?)=when(failure) {
    401,403 -> LibraryAvailability.AccessDenied
    -2,404,405,501 -> LibraryAvailability.InvalidResponse
    else -> LibraryAvailability.Unavailable
}
