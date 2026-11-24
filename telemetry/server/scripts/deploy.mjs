import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const config = "wrangler.production.json";
const v = JSON.parse(readFileSync(config, "utf8"));
const d1 = v.d1_databases?.[0];
const hosts = v.routes?.map(route => route.pattern) ?? [];
const workersDev = v.workers_dev === true && hosts.length === 0;
const customDomain = v.workers_dev === false && hosts.length > 0 &&
  hosts.every(host => /^[a-z0-9.-]+$/.test(host) && !host.endsWith("example.com"));
const mode = v.vars?.ADMIN_AUTH_MODE ?? "access";
const secured = mode === "password" && v.secrets?.required?.includes("ADMIN_CREDENTIAL_SHA256") ||
  mode === "access" && /^[a-f0-9]{64}$/.test(v.vars?.ACCESS_AUDIENCE) &&
  /^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(v.vars?.ACCESS_ISSUER);
if (v.name !== "torrframe-telemetry" || v.vars?.ENVIRONMENT !== "production" ||
    v.d1_databases?.length !== 1 || d1?.binding !== "DB" || d1.database_name !== "torrframe-analytics" ||
    !/^[a-f0-9-]{36}$/.test(d1.database_id) ||
    (!workersDev && !customDomain) || !secured || !v.secrets?.required?.includes("IDENTITY_HMAC_SECRET") ||
    v.preview_urls !== false || v.observability?.enabled !== false ||
    v.assets?.run_worker_first !== true) throw Error("Check the standalone production configuration.");

const [command, secretsFile] = process.argv.slice(2);
const args = command === "migrate" ? ["d1","migrations","apply","DB","--remote"] :
    command === "publish" ? ["deploy"] : command === "dry-run" ? ["deploy","--dry-run"] : null;
if (!args || (secretsFile && command === "migrate")) throw Error("Use npm run deploy -- migrate, dry-run [secrets file] or publish [secrets file].");
if (secretsFile) args.push("--secrets-file", secretsFile);
if (command !== "dry-run") {
  const inventory = spawnSync("./node_modules/.bin/wrangler", ["d1","list","--json","--config",config], {encoding:"utf8"});
  if (inventory.status !== 0 || !JSON.parse(inventory.stdout).some(item => item.uuid === d1.database_id && item.name === "torrframe-analytics"))
    throw Error("The configured D1 ID does not belong to torrframe-analytics in this account.");
}
const result = spawnSync("./node_modules/.bin/wrangler", [...args,"--config",config], { stdio: "inherit" });
process.exit(result.status ?? 1);
