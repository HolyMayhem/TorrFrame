import { createRemoteJWKSet, jwtVerify } from "jose";
import { DAY, dayOf, dayTime, HttpError, readHeartbeat } from "./contract";
import { cleanup, countryFrom, dailyLimit, pseudonym, storeHeartbeat } from "./storage";
import { statistics } from "./stats";

type Limiter = { limit(input: { key: string }): Promise<{ success: boolean }> };
interface Env {
  DB: D1Database;
  ASSETS: Fetcher;
  INGRESS: Limiter;
  INSTALLATION: Limiter;
  ADMIN: Limiter;
  IDENTITY_HMAC_SECRET?: string;
  LOCAL_ADMIN_PASSWORD?: string;
  ADMIN_AUTH_MODE?: "access" | "password";
  ADMIN_CREDENTIAL_SHA256?: string;
  ACCESS_ISSUER?: string;
  ACCESS_AUDIENCE?: string;
  ENVIRONMENT: "production" | "development";
  DAILY_LIMIT?: string;
  LATEST_BUILD?: string;
}
const headers = {
  "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};
function json(data: unknown, status = 200, extra: Record<string,string> = {}): Response {
  return new Response(status === 204 ? null : JSON.stringify(data), {
    status, headers: { ...headers, "Content-Type": "application/json; charset=utf-8", ...extra },
  });
}
const assets = new Map([
  ["/admin","/admin/index.html"], ["/admin/","/admin/index.html"], ["/admin/index.html","/admin/index.html"],
  ["/admin/app.js","/admin/app.js"], ["/admin/styles.css","/admin/styles.css"], ["/admin/brand.png","/admin/brand.png"],
]);
let access: { issuer: string; keys: ReturnType<typeof createRemoteJWKSet> } | undefined;
function equalCredential(actual: string, expected: string): boolean {
  if (actual.length !== expected.length) return false;
  let unequal = 0;
  for (let n = 0; n < expected.length; n++) unequal |= actual.charCodeAt(n) ^ expected.charCodeAt(n);
  return unequal === 0;
}
function adminRealm(env: Env, url: URL): string | undefined {
  if (env.ENVIRONMENT === "development" && ["localhost","127.0.0.1","[::1]"].includes(url.hostname))
    return "Local TorrFrame Analytics";
  if (env.ENVIRONMENT === "production" && env.ADMIN_AUTH_MODE === "password" && url.protocol === "https:")
    return "TorrFrame Analytics";
}
async function admin(request: Request, env: Env, url: URL, realm: string | undefined): Promise<boolean> {
  if (realm && env.ENVIRONMENT === "development") {
    const secret = env.LOCAL_ADMIN_PASSWORD;
    if (!secret || secret.length < 24) return false;
    return equalCredential(request.headers.get("Authorization") ?? "", "Basic " + btoa("admin:" + secret));
  }
  if (env.ENVIRONMENT !== "production" || url.protocol !== "https:") return false;
  if (env.ADMIN_AUTH_MODE === "password") {
    const expected = env.ADMIN_CREDENTIAL_SHA256;
    const authorization = request.headers.get("Authorization");
    if (!expected || !/^[0-9a-f]{64}$/.test(expected) || !authorization) return false;
    const credential = /^Basic ([A-Za-z0-9+/]{94}==)$/i.exec(authorization)?.[1];
    if (credential) {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("Basic " + credential));
      const actual = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
      if (equalCredential(actual, expected)) return true;
    }
    await limit(env.ADMIN, "authentication");
    return false;
  }
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  const issuer = env.ACCESS_ISSUER;
  if (!token || !env.ACCESS_AUDIENCE || !issuer || !/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(issuer)) return false;
  try {
    if (access?.issuer !== issuer) access = { issuer, keys: createRemoteJWKSet(new URL(issuer + "/cdn-cgi/access/certs")) };
    const { payload } = await jwtVerify(token, access.keys, {
      issuer, audience: env.ACCESS_AUDIENCE, algorithms: ["RS256"], requiredClaims: ["exp", "sub", "type"],
    });
    return payload.type === "app";
  } catch { return false; }
}
async function limit(binding: Limiter, key: string) {
  if (!(await binding.limit({ key })).success) throw new HttpError(429, "Rate limited", 60);
}
export async function handle(request: Request, env: Env, now = Date.now()): Promise<Response> {
  try {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") return json({ status: "alive" });
    if (url.pathname === "/v1/heartbeat") {
      if (request.method !== "POST") throw new HttpError(405, "POST required");
      if (url.search) throw new HttpError(400, "Query parameters not accepted");
      const production = env.ENVIRONMENT === "production" && url.protocol === "https:";
      const development = env.ENVIRONMENT === "development" && ["localhost","127.0.0.1","[::1]"].includes(url.hostname);
      if (!production && !development) throw new HttpError(400, "Invalid telemetry environment");
      await limit(env.INGRESS, "heartbeat");
      const value = await readHeartbeat(request, now);
      const id = await pseudonym(env.IDENTITY_HMAC_SECRET, value.installationID);
      await limit(env.INSTALLATION, id);
      const stored = await storeHeartbeat(env.DB, value, id, countryFrom(request.cf?.country), now, dailyLimit(env.DAILY_LIMIT));
      if (!stored) throw new HttpError(429, "Daily intake limit", Math.ceil((dayTime(dayOf(now)) + DAY - now) / 1000));
      return json(null, 204, { "X-Telemetry-Day": dayOf(now),
        ...(stored.acknowledgedUsage ? { "X-Telemetry-Usage": stored.acknowledgedUsage } : {}) });
    }
    const asset = assets.get(url.pathname);
    const stats = url.pathname === "/admin/api/stats";
    if (!asset && !stats) throw new HttpError(404, "Not found");
    if (request.method !== "GET") throw new HttpError(405, "GET required");
    const realm = adminRealm(env, url);
    if (!await admin(request, env, url, realm))
      return json({ error: "Authorization required" }, 401,
        realm ? { "WWW-Authenticate": `Basic realm="${realm}"` } : {});
    if (stats) {
      await limit(env.ADMIN, "statistics");
      return json({ ...await statistics(env.DB, url, now, env.LATEST_BUILD), testEnvironment: env.ENVIRONMENT === "development" });
    }
    const response = await env.ASSETS.fetch(new URL(asset!, url.origin));
    const resultHeaders = new Headers(response.headers);
    for (const [key,value] of Object.entries(headers)) resultHeaders.set(key,value);
    return new Response(response.body, { status: response.status, headers: resultHeaders });
  } catch (error) {
    // Raw requests, IP addresses, identifiers, exceptions and bodies are never logged.
    return error instanceof HttpError
      ? json({ error: error.message }, error.status, error.retry ? { "Retry-After": String(error.retry) } : {})
      : json({ error: "Service temporarily unavailable" }, 503);
  }
}
export default {
  fetch(request,env) { return handle(request,env); },
  async scheduled(event,env) { await cleanup(env.DB,event.scheduledTime); },
} satisfies ExportedHandler<Env>;
