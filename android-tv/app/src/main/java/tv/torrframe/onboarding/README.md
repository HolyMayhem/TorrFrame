# First-time setup

This directory is the self-contained setup feature, not a separate Android application.

- `SetupProgress`: stable step identifiers and ordering.
- `SetupController`: progress in the separate `first-run-setup` preferences file.
- `SetupWizard`: presentation and animation; no AppGraph or persistence dependency.
- `SetupPhoneCard`: lifecycle-bound QR preview using the existing pairing service.
- `SetupHost`: the only adapter to existing settings, phone remote and server commands.

Application integration consists of initializing progress before settings migrations,
composing `SetupHost` after the startup cover, and providing repeat-setup actions.
Existing installations are marked finished on their first upgrade. New installations
start at Welcome; saved unfinished steps resume after process death. Replaying the
wizard does not clear application settings, server data or the library.

Settings changes use the original stores. Remote pairing and network readiness are
optional; the wizard never waits for catalog requests. Server connection changes
use the shared server action; the wizard waits for persisted configuration, not its
network health check. Startup and catalog initialization continue independently.
