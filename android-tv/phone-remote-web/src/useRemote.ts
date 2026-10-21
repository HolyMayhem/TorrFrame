import { t } from "./i18n";
import { useCallback, useEffect, useRef, useState } from "react";
import { getOperation, getState, RemoteError, request, submit } from "./api";
import { useSyncStatus } from "./useSyncStatus";
import { applyEvent } from "./protocol";
import type { Command, Operation, RemoteEvent, Snapshot } from "./types";
export function useRemote() {
  const { syncPhase, setDraftDirty, beginSync, finishSync } = useSyncStatus();
  const [state, setState] = useState<Snapshot | null>(null),
    [connected, setConnected] = useState(false),
    [pairRequired, setPairRequired] = useState(false),
    [error, setError] = useState(""),
    [pairGeneration, setPairGeneration] = useState(0);
  const requests = useRef(new AbortController());
  const pendingOperations = useRef(new Set<() => void>());
  const latest = useRef<Snapshot | null>(null),
    socket = useRef<WebSocket | null>(null),
    refreshing = useRef<Promise<void> | null>(null),
    refreshAgain = useRef(false),
    queued = useRef<RemoteEvent[]>([]),
    listeners = useRef(new Map<string, (op: Operation) => void>());
  const refresh = useCallback(async () => {
    if (refreshing.current) {
      refreshAgain.current = true;
      return refreshing.current;
    }
    const owner = requests.current;
    const work = async () => {
      do {
        refreshAgain.current = false;
        let next = await getState(owner.signal);
        if (owner.signal.aborted) return;
        const events = queued.current;
        queued.current = [];
        for (const event of events) {
          if (
            event.type === "sync.ready" &&
            event.epoch === next.epoch &&
            event.sequence <= next.sequence
          )
            continue;
          const result = applyEvent(next, event);
          next = result.state;
          if (result.refresh) refreshAgain.current = true;
        }
        latest.current = next;
        setState(next);
        setPairRequired(false);
      } while (refreshAgain.current);
    };
    const pending = work();
    refreshing.current = pending;
    try {
      await pending;
    } finally {
      if (refreshing.current === pending) refreshing.current = null;
    }
  }, []);
  useEffect(() => {
    requests.current = new AbortController();
    const owner = requests.current;
    let disposed = false,
      attempt = 0,
      timer: ReturnType<typeof setTimeout> | undefined;
    const fail = (e: unknown) => {
      const err = e instanceof Error ? e.message : t("Нет связи");
      if (!disposed) {
        setConnected(false);
        setError(err);
        if (socket.current?.readyState === WebSocket.OPEN)
          socket.current.close();
        if (e instanceof RemoteError && e.authorization) {
          setPairRequired(true);
          latest.current = null;
          setState(null);
        }
      }
    };
    const connect = async () => {
      if (disposed) return;
      try {
        await refresh();
        if (disposed) return;
        const current = latest.current!;
        const ws = new WebSocket(
          `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/events`,
          ["phone.v1", current.csrf],
        );
        socket.current = ws;
        ws.onopen = () => {
          if (disposed) {
            ws.close();
            return;
          }
          attempt = 0;
          setConnected(true);
          setError("");
        };
        ws.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data) as RemoteEvent;
            if (message.type === "operation.updated") {
              const op = message.data as Operation;
              listeners.current.get(op.id)?.(op);
            }
            if (refreshing.current) {
              if (queued.current.length >= 256) {
                queued.current = [];
                refreshAgain.current = true;
              }
              queued.current.push(message);
              return;
            }
            if (latest.current) {
              const result = applyEvent(latest.current, message);
              latest.current = result.state;
              setState(result.state);
              if (result.refresh) void refresh().catch(fail);
            }
          } catch {
            void refresh().catch(fail);
          }
        };
        ws.onclose = () => {
          if (disposed) return;
          setConnected(false);
          setError(t("Связь прервана. Переподключаемся…"));
          timer = setTimeout(
            () => void connect(),
            Math.min(15000, 500 * 2 ** attempt++),
          );
        };
      } catch (e) {
        fail(e);
        if (!disposed && !(e instanceof RemoteError && e.authorization))
          timer = setTimeout(
            () => void connect(),
            Math.min(15000, 500 * 2 ** attempt++),
          );
      }
    };
    const initial = async () => {
      const params = new URLSearchParams(location.hash.slice(1));
      const token = params.get("pair");
      if (token) {
        history.replaceState(null, "", location.pathname);
        try {
          await request("/pair", {
            token,
            device: /iPad/i.test(navigator.userAgent)
              ? "iPad"
              : /iPhone/i.test(navigator.userAgent)
                ? "iPhone"
                : /Android/i.test(navigator.userAgent)
                  ? "Android"
                  : t("Браузер"),
          });
        } catch (e) {
          try {
            await refresh();
          } catch {
            fail(e);
            return;
          }
        }
      }
      await connect();
    };
    void initial();
    return () => {
      disposed = true;
      clearTimeout(timer);
      owner.abort();
      for (const cancel of pendingOperations.current) cancel();
      pendingOperations.current.clear();
      listeners.current.clear();
      queued.current = [];
      refreshing.current = null;
      socket.current?.close();
      socket.current = null;
    };
  }, [refresh, pairGeneration]);
  const pairCode = useCallback(async (code: string) => {
    await request("/pair", { code, device: t("Браузер") });
    setError("");
    setPairRequired(false);
    setPairGeneration((value) => value + 1);
  }, []);
  const command = useCallback(
    async <T = Record<string, unknown>>(
      type: Command,
      args: Record<string, unknown> = {},
    ): Promise<T> => {
      const current = latest.current;
      if (
        !current ||
        !socket.current ||
        socket.current.readyState !== WebSocket.OPEN
      )
        throw new RemoteError(
          t("Дождитесь восстановления связи с телевизором."),
        );
      const syncing = beginSync(type);
      let synced = false;
      try {
        const owner = requests.current;
        const { operationId } = await submit(
          type,
          args,
          current.csrf,
          owner.signal,
        );
        if (owner.signal.aborted) throw new RemoteError(t("Операция отменена."));
        const result = await new Promise<T>((resolve, reject) => {
          let finished = false;
          let poll: ReturnType<typeof setTimeout> | undefined;
          const deadline = setTimeout(
            () =>
              finish(
                undefined,
                new RemoteError(
                  t(
                    "Операция ещё выполняется. Проверьте её состояние на телевизоре.",
                  ),
                ),
              ),
            type === "search" ? 245000 : 125000,
          );
          const cleanup = () => {
            finished = true;
            clearTimeout(deadline);
            clearTimeout(poll);
            listeners.current.delete(operationId);
            pendingOperations.current.delete(cancel);
          };
          const finish = (op?: Operation, err?: Error) => {
            if (finished) return;
            if (err) {
              cleanup();
              reject(err);
              return;
            }
            if (!op || op.status === "running") return;
            cleanup();
            if (op.status === "completed") resolve(op.result as T);
            else reject(new RemoteError(op.error || t("Операция отменена.")));
          };
          const check = async () => {
            if (finished) return;
            try {
              const op = await getOperation(operationId, owner.signal);
              finish(op);
              if (!finished) poll = setTimeout(() => void check(), 2500);
            } catch (e) {
              if (e instanceof RemoteError && e.authorization)
                finish(undefined, e);
              else if (!finished) poll = setTimeout(() => void check(), 2500);
            }
          };
          const cancel = () =>
            finish(undefined, new RemoteError(t("Операция отменена.")));
          pendingOperations.current.add(cancel);
          listeners.current.set(operationId, (op) => {
            if (op.status !== "running") void check();
          });
          void check();
        });
        // Read back the application-owned snapshot after persistence, including missed events.
        if (syncing) await refresh();
        synced = true;
        return result;
      } finally { if (syncing) finishSync(synced); }
    },
    [beginSync, finishSync, refresh],
  );
  return { state, connected, pairRequired, error, command, refresh, pairCode, syncPhase, setDraftDirty };
}
export type Remote = ReturnType<typeof useRemote>;
