import { useCallback, useEffect, useState } from "react";
import type { Command } from "./types";
export type SyncPhase = "idle" | "changed" | "syncing" | "done" | "failed";
const writes = new Set<Command>([
  "settings.update", "server.connect", "server.boot", "server.lan",
  "engine.save", "engine.reset", "library.add", "library.magnet", "library.remove",
  "metadata.refresh", "metadata.reset", "metadata.rename", "metadata.select", "catalog.toggle",
]);
export function useSyncStatus() {
  const [drafts, setDrafts] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState(0);
  const [result, setResult] = useState<SyncPhase>("idle");
  const setDraftDirty = useCallback((scope: string, dirty: boolean) => {
    setDrafts(previous => {
      if (previous.has(scope) === dirty) return previous;
      const next = new Set(previous);
      if (dirty) next.add(scope); else next.delete(scope);
      return next;
    });
  }, []);
  const beginSync = useCallback((type: Command) => {
    if (!writes.has(type)) return false;
    setPending(count => count + 1);
    setResult("idle");
    return true;
  }, []);
  const finishSync = useCallback((success: boolean) => {
    setPending(count => Math.max(0, count - 1));
    // A later concurrent success must not conceal a failed write.
    setResult(previous => !success || previous === "failed" ? "failed" : "done");
  }, []);
  useEffect(() => {
    if (pending || result !== "done") return;
    const timer = setTimeout(() => setResult("idle"), 3500);
    return () => clearTimeout(timer);
  }, [pending, result]);
  const syncPhase: SyncPhase = pending ? "syncing" : result === "failed" ? "failed"
    : drafts.size ? "changed" : result;
  return { syncPhase, setDraftDirty, beginSync, finishSync };
}
