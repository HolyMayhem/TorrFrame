export const DAY = 86_400_000;
export const USAGE_DAYS = 7;
export const HISTORY_DAYS = 90;
export const FEATURES = [
  "builtinTorrServer", "externalTorrServer", "builtinPlayer", "externalPlayer", "phoneRemote",
  "torrentSearch", "jackett", "tmdb", "cub", "afr", "favorites", "watchLater",
] as const;
export type Feature = typeof FEATURES[number];
const BRANDS = ["Xiaomi", "Google", "NVIDIA", "Sony", "TCL", "Hisense", "Philips", "Amazon",
  "onn.", "Mecool", "Homatics", "Other", "Unknown"] as const;
const MODELS = ["Mi Box S", "Mi TV Stick", "Xiaomi TV Box S", "Chromecast with Google TV",
  "Google TV Streamer", "SHIELD TV", "SHIELD TV Pro", "Android TV Emulator", "Other", "Unknown"] as const;
const ARCH = ["arm64-v8a", "armeabi-v7a", "Unknown"] as const;
const RAM = ["lt1", "1to2", "2to3", "3to4", "4to6", "6to8", "gte8", "Unknown"] as const;
const DEVICES = ["Android TV", "Other", "Unknown"] as const;
export const dayOf = (time: number) => new Date(time).toISOString().slice(0, 10);
export function dayTime(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return NaN;
  const time = Date.parse(value + "T00:00:00Z");
  return Number.isFinite(time) && dayOf(time) === value ? time : NaN;
}
export class HttpError extends Error {
  constructor(readonly status: number, text: string, readonly retry?: number) { super(text); }
}
function invalid(): never { throw new HttpError(400, "Invalid heartbeat"); }
function object(value: unknown, keys: readonly string[], optional?: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid();
  const entry = value as Record<string, unknown>;
  if (Object.keys(entry).some(key => !keys.includes(key)) ||
    keys.some(key => key !== optional && !Object.hasOwn(entry, key))) invalid();
  return entry;
}
function oneOf<const T extends readonly string[]>(value: unknown, options: T): T[number] {
  if (typeof value !== "string" || !options.includes(value)) invalid();
  return value;
}
function integer(value: unknown, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) invalid();
  return value;
}
export function validate(value: unknown, now: number) {
  const entry = object(value, ["schemaVersion", "installationID", "appVersion", "appVersionCode",
    "androidApiLevel", "manufacturer", "deviceModel", "cpuArchitecture", "ramBucket",
    "deviceType", "interfaceLanguage", "theme", "usage"], "usage");
  if (entry.schemaVersion !== 1 || typeof entry.installationID !== "string" ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(entry.installationID) ||
    typeof entry.appVersion !== "string" ||
    !/^\d{1,3}\.\d{1,3}(?:\.\d{1,3})?(?:-(?:alpha|beta|rc)\.?[0-9]{1,4})?$/.test(entry.appVersion)) invalid();
  let usage: { date: string; mask: number } | null = null;
  if (Object.hasOwn(entry, "usage")) {
    const report = object(entry.usage, ["date", "features"]);
    const today = dayTime(dayOf(now));
    if (typeof report.date !== "string") invalid();
    const time = dayTime(report.date);
    if (!Number.isFinite(time) || time < today - USAGE_DAYS * DAY || time >= today) invalid();
    const values = object(report.features, FEATURES);
    let mask = 0;
    for (const [bit, feature] of FEATURES.entries()) {
      if (typeof values[feature] !== "boolean") invalid();
      if (values[feature]) mask |= 1 << bit;
    }
    usage = { date: report.date, mask };
  }
  return {
    installationID: entry.installationID.toLowerCase(), appVersion: entry.appVersion,
    appVersionCode: integer(entry.appVersionCode, 1, 2_100_000_000),
    androidApiLevel: integer(entry.androidApiLevel, 26, 99),
    manufacturer: oneOf(entry.manufacturer, BRANDS), deviceModel: oneOf(entry.deviceModel, MODELS),
    cpuArchitecture: oneOf(entry.cpuArchitecture, ARCH), ramBucket: oneOf(entry.ramBucket, RAM),
    deviceType: oneOf(entry.deviceType, DEVICES), interfaceLanguage: oneOf(entry.interfaceLanguage, ["ru", "en"]),
    theme: oneOf(entry.theme, ["light", "dark"]), usage,
  };
}
export type Heartbeat = ReturnType<typeof validate>;

export async function readHeartbeat(req: Request, now: number): Promise<Heartbeat> {
  const max = 2048;
  if (req.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() !== "application/json")
    throw new HttpError(415, "Expected application/json");
  if (Number(req.headers.get("Content-Length")) > max) throw new HttpError(413, "Payload too large");
  if (!req.body) invalid();
  const reader = req.body.getReader();
  const buffer = new Uint8Array(max);
  let count = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (count + value.length > max) {
        await reader.cancel();
        throw new HttpError(413, "Payload too large");
      }
      buffer.set(value, count);
      count += value.length;
    }
    return validate(JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(buffer.subarray(0, count))), now);
  } catch (error) {
    if (error instanceof HttpError) throw error;
    invalid();
  } finally {
    reader.releaseLock();
  }
}
