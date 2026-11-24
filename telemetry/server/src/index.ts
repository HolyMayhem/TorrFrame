import { WorkerEntrypoint } from "cloudflare:workers";
import { statistics } from "./stats";
import type { Env } from "./http";

export { default } from "./http";

// Named service binding only. The default HTTP handler never dispatches these methods.
export class HubAnalytics extends WorkerEntrypoint<Env> {
  health() {
    return { contract: 1, source: "torrframe" } as const;
  }

  async snapshot(input: { start: string; end: string; section: string }) {
    const { start, end, section } = input;
    if (!["summary", "overview", "audience", "devices", "geography", "versions", "features"].includes(section) ||
        typeof start !== "string" || typeof end !== "string" ||
        !/^\d{4}-\d{2}-\d{2}$/.test(start) || !/^\d{4}-\d{2}-\d{2}$/.test(end)) {
      throw new Error("Invalid analytics request");
    }
    const url = new URL("https://internal.invalid");
    url.searchParams.set("from", start);
    url.searchParams.set("to", end);
    try {
      return { ...this.health(), data: await statistics(this.env.DB, url, Date.now(), this.env.LATEST_BUILD, section) };
    } catch {
      throw new Error("Analytics unavailable");
    }
  }
}
