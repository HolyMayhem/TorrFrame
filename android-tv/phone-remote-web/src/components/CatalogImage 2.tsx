import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { CatalogNetworkSettings } from "../types";
import { imageRoutes, orderedImageRoutes, imageRouteFailed, imageRouteSucceeded, restoreImageRoute } from "../catalogImages";

export const CatalogNetworkContext = createContext<CatalogNetworkSettings | undefined>(undefined);
export function CatalogImage({ src }: { src: string }) {
  const settings = useContext(CatalogNetworkContext);
  const routes = imageRoutes(src, settings);
  return <ImageAttempts key={JSON.stringify(routes)} routes={routes} />;
}
function ImageAttempts({ routes }: { routes: string[] }) {
  const [revision, setRevision] = useState(0);
  const attempts = useMemo(() => { restoreImageRoute(); return orderedImageRoutes(routes); }, [revision]);
  const [attempt, setAttempt] = useState(0);
  const [ready, setReady] = useState(false);
  const image = useRef<HTMLImageElement>(null);
  const retries = useRef(0);
  useEffect(() => {
    const recover = () => { if (ready) return; retries.current = 0; setAttempt(0); setReady(false); setRevision(value => value + 1); };
    const visible = () => { if (document.visibilityState === "visible" && !ready) recover(); };
    window.addEventListener("online", recover); document.addEventListener("visibilitychange", visible);
    return () => { window.removeEventListener("online", recover); document.removeEventListener("visibilitychange", visible); };
  }, [ready]);
  useEffect(() => {
    const node = image.current;
    if (!node || ready || attempt >= attempts.length) return;
    // Start deadlines only when a lazy image approaches the viewport.
    let timer: ReturnType<typeof setTimeout> | undefined;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting) && !timer) timer = setTimeout(() => {
        imageRouteFailed(attempts[attempt]); setAttempt(value => value === attempt ? value + 1 : value);
      }, 4_000);
    }, { rootMargin: "200px" });
    observer.observe(node);
    return () => { observer.disconnect(); clearTimeout(timer); };
  }, [attempt, attempts, ready]);
  useEffect(() => {
    if (attempt < attempts.length || retries.current >= 2) return;
    const timer = setTimeout(() => {
      retries.current++; setAttempt(0); setRevision(value => value + 1);
    }, 1_500 * (retries.current + 1));
    return () => clearTimeout(timer);
  }, [attempt, attempts]);
  if (attempt >= attempts.length) return null;
  return <img key={`${revision}:${attempt}`} ref={image} src={attempts[attempt]} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer"
    onLoad={() => { imageRouteSucceeded(attempts[attempt]); setReady(true); }}
    onError={() => { imageRouteFailed(attempts[attempt]); setReady(false); setAttempt(value => value === attempt ? value + 1 : value); }} />;
}
