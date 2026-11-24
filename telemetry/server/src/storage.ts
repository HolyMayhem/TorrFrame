import { DAY, dayOf, HISTORY_DAYS, type Heartbeat } from "./contract";

export async function pseudonym(secret: string | undefined, installationID: string): Promise<string> {
  if (!secret || secret.length < 32) throw new Error("Missing identity key");
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode("torrframe:v1:" + installationID));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}

const COUNTRIES = new Set(("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
  "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR " +
  "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP " +
  "KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ " +
  "NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ " +
  "TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(" "));

export function countryFrom(value: unknown): string {
  return typeof value === "string" && COUNTRIES.has(value) ? value : "XX";
}

export function dailyLimit(raw: string | undefined): number {
  const value = Number(raw);
  return Number.isInteger(value) && value >= 1 ? Math.min(value, 2000) : 1000;
}

export async function storeHeartbeat(db: D1Database, value: Heartbeat, id: string, country: string, now: number, limit: number) {
  const day = dayOf(now);
  const [, member] = await db.batch<{ report_day: string | null }>([
    db.prepare(`INSERT INTO activity(id,day,received_at,version,build,api,manufacturer,model,architecture,
      ram,device,language,theme,country,report_day,report_mask)
      SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
      WHERE COALESCE((SELECT active FROM daily WHERE day = ?), 0) < ?
      ON CONFLICT(day,id) DO NOTHING`).bind(id, day, now, value.appVersion, value.appVersionCode, value.androidApiLevel,
      value.manufacturer, value.deviceModel, value.cpuArchitecture, value.ramBucket, value.deviceType, value.interfaceLanguage,
      value.theme, country, value.usage?.date ?? null, value.usage?.mask ?? null, day, limit),
    db.prepare("SELECT report_day FROM activity WHERE day = ? AND id = ?").bind(day, id),
  ]);
  if (!member.results.length) return null;
  const original = member.results[0]?.report_day;
  return {
    acknowledgedUsage: value.usage?.date && original === value.usage.date ? original : null,
  };
}

export async function cleanup(db: D1Database, now: number): Promise<void> {
  const cutoff = dayOf(now - (HISTORY_DAYS - 1) * DAY);
  await db.batch([
    db.prepare(`DELETE FROM activity WHERE (day,id) IN
      (SELECT day,id FROM activity WHERE day < ? ORDER BY day LIMIT 1000)`).bind(cutoff),
    db.prepare(`DELETE FROM feature_reports WHERE (day,id) IN
      (SELECT day,id FROM feature_reports WHERE day < ? ORDER BY day LIMIT 1000)`).bind(cutoff),
    db.prepare(`DELETE FROM installations WHERE id IN
      (SELECT id FROM installations WHERE last_seen < ? ORDER BY last_seen LIMIT 1000)`).bind(now - 400 * DAY),
    db.prepare(`DELETE FROM daily WHERE day IN
      (SELECT day FROM daily WHERE day < ? ORDER BY day LIMIT 1000)`).bind(dayOf(now - 729 * DAY)),
  ]);
}
