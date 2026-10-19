import type { CatalogNetworkSettings } from "./types";

export const publicImageMirrors = ["https://imagetmdb.com/", "https://nl.imagetmdb.com/", "https://de.imagetmdb.com/"];
const unhealthy = new Map<string, number>();
function hostOf(url: string) { try { return new URL(url, "https://localhost/").host; } catch { return ""; } }
let preferred = "";
export function imageRoutes(src: string, settings?: CatalogNetworkSettings): string[] {
  try {
    const original = new URL(src);
    if (original.protocol !== "https:" || original.hostname !== "image.tmdb.org" || original.port) return [src];
    const bases = [...(settings?.imageMirrors ?? []).slice(0, 3), ...publicImageMirrors];
    const mirrors = bases.flatMap(value => {
      try {
        const base = new URL(value);
        if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) return [];
        base.pathname = base.pathname.replace(/\/$/, "") + original.pathname;
        base.search = original.search;
        return [base.href];
      } catch { return []; }
    });
    return [...new Set(settings?.route === "Proxy" ? mirrors : [src, ...mirrors])];
  } catch { return [src]; }
}
export function orderedImageRoutes(routes: string[]): string[] {
  const now = Date.now();
  for (const [host, until] of unhealthy) if (until <= now) unhealthy.delete(host);
  const healthy = routes.filter(url => !unhealthy.has(hostOf(url)));
  return [...(healthy.length ? healthy : routes)].sort((a, b) => Number(hostOf(b) === preferred) - Number(hostOf(a) === preferred));
}
export function imageRouteFailed(url: string) {
  const host = hostOf(url);
  unhealthy.delete(host); unhealthy.set(host, Date.now() + 60_000);
  while (unhealthy.size > 16) unhealthy.delete(unhealthy.keys().next().value!);
}
export function imageRouteSucceeded(url: string) {
  preferred = hostOf(url); unhealthy.delete(preferred);
  try { if (typeof window !== "undefined") localStorage.setItem("torrframe-image-host", preferred); } catch { /* Private browsing. */ }
}
export function restoreImageRoute() {
  try { if (typeof window !== "undefined") preferred = localStorage.getItem("torrframe-image-host") ?? ""; } catch { /* Private browsing. */ }
}
