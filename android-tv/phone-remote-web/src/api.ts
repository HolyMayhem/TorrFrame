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
): Promise<T> {
  const controller = new AbortController();
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
          "Откройте новый QR-код на телевизоре для сопряжения.",
          true,
        );
      throw new RemoteError(
        response.status === 403
          ? "Запрос отклонён. Переподключитесь через QR-код."
          : response.status === 429
            ? "Слишком много попыток. Подождите минуту."
            : "Телевизор не принял запрос. Повторите попытку.",
      );
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof RemoteError) throw error;
    throw new RemoteError(
      "Нет связи с телевизором. Проверьте Wi-Fi. Если адрес TV изменился, откройте новый QR-код.",
    );
  } finally {
    clearTimeout(timeout);
  }
}
export const getState = () => request<Snapshot>("/api/state");
export async function submit(
  command: Command,
  args: Record<string, unknown>,
  csrf: string,
) {
  // HTTPS-only crypto.randomUUID is unavailable on LAN HTTP in some browsers.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const requestId = Array.from(bytes, (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  return request<{ operationId: string }>(
    "/api/commands",
    { requestId, type: command, args },
    csrf,
  );
}
export const getOperation = (id: string) =>
  request<Operation>(`/api/operations/${encodeURIComponent(id)}`);
