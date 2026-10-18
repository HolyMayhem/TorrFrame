import { t, webLanguage } from "./i18n";
import type { Command, Operation, Snapshot } from "./types";
export class RemoteError extends Error {
  constructor(
    message: string,
    public readonly authorization = false,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  body?: unknown,
  csrf?: string,
  signal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) abort();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers:
        body === undefined
          ? {}
          : {
              "Content-Type": "application/json",
              "X-Phone-Remote": "1",
              ...(csrf ? { "X-Phone-CSRF": csrf } : {}),
            },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      if (response.status === 401)
        throw new RemoteError(
          path === "/pair"
            ? t(
                "Код или QR-ссылка неверны либо истекли. Получите новый код на TV.",
              )
            : t(
                "Откройте подключение Phone Remote на TV: используйте QR или временный код.",
              ),
          true,
        );
      throw new RemoteError(
        response.status === 403
          ? t("Запрос отклонён. Подключитесь заново через QR или код.")
          : response.status === 429
            ? t("Слишком много попыток. Подождите минуту.")
            : t("Телевизор не принял запрос. Повторите попытку."),
      );
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof RemoteError) throw error;
    throw new RemoteError(
      t(
        "Нет связи с телевизором. Проверьте Wi-Fi. Если адрес TV изменился, откройте новый QR-код.",
      ),
    );
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}
export const getState = (signal?: AbortSignal) =>
  request<Snapshot>("/api/state", undefined, undefined, signal);
export async function submit(
  command: Command,
  args: Record<string, unknown>,
  csrf: string,
  signal?: AbortSignal,
) {
  // HTTPS-only crypto.randomUUID is unavailable on LAN HTTP in some browsers.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const requestId = Array.from(bytes, (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  return request<{ operationId: string }>(
    "/api/commands",
    {
      requestId,
      type: command,
      language: webLanguage(),
      args: { ...args, _language: webLanguage() },
    },
    csrf,
    signal,
  );
}
export const getOperation = (id: string, signal?: AbortSignal) =>
  request<Operation>(
    `/api/operations/${encodeURIComponent(id)}`,
    undefined,
    undefined,
    signal,
  );
