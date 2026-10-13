import type { Snapshot, RemoteEvent } from "./types";
export function applyEvent(
  current: Snapshot,
  event: RemoteEvent,
): { state: Snapshot; refresh: boolean } {
  if (event.v !== 1 || event.epoch !== current.epoch)
    return { state: current, refresh: true };
  if (event.type === "sync.ready") return { state: current, refresh: true };
  if (event.sequence <= current.sequence)
    return { state: current, refresh: false };
  if (event.sequence !== current.sequence + 1)
    return { state: current, refresh: true };
  const next = { ...current, sequence: event.sequence };
  switch (event.type) {
    case "library.updated":
      return { state: next, refresh: true };
    case "settings.updated":
      next.settings = event.data as Snapshot["settings"];
      break;
    case "playback.updated":
      next.playback = event.data as Snapshot["playback"];
      break;
    case "torrserver.updated":
      next.server = event.data as Snapshot["server"];
      break;
    case "updates.updated":
      next.updates = event.data as Snapshot["updates"];
      break;
    case "operation.updated":
      break;
    default:
      return { state: current, refresh: true };
  }
  return { state: next, refresh: false };
}
