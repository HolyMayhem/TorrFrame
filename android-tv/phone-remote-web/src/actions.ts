import { t } from "./i18n";
import { useState } from "react";
import type { Remote } from "./useRemote";
import type { Command } from "./types";
export interface Actions {
  remote: Remote;
  notify: (text: string) => void;
  confirm: (title: string, description: string) => Promise<boolean>;
  openItem: (hash: string) => void;
  searchFor: (
    query: string,
    preset?: Partial<import("./types").SearchPreset>,
  ) => void;
}
export function useAction(actions: Actions) {
  const [busy, setBusy] = useState(false);
  const run = async <T = Record<string, unknown>>(
    type: Command,
    args: Record<string, unknown> = {},
  ): Promise<T | undefined> => {
    if (busy) return;
    setBusy(true);
    try {
      return await actions.remote.command<T>(type, args);
    } catch (e) {
      actions.notify(
        e instanceof Error ? e.message : t("Операция не выполнена."),
      );
    } finally {
      setBusy(false);
    }
  };
  return { busy, run, disabled: busy || !actions.remote.connected };
}
