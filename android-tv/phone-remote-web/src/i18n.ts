import { useSyncExternalStore } from "react";
import { translate } from "./translations";
export type WebLanguage = "ru" | "en";
let language: WebLanguage = "en";
try {
  const saved = localStorage.getItem("torrframe.web.language");
  language =
    saved === "ru" || saved === "en"
      ? saved
      : navigator.language.startsWith("ru")
        ? "ru"
        : "en";
} catch {}
const listeners = new Set<() => void>();
export function webLanguage() {
  return language;
}
export function setWebLanguage(value: WebLanguage) {
  language = value;
  try {
    localStorage.setItem("torrframe.web.language", value);
  } catch {}
  document.documentElement.lang = value;
  for (const listener of listeners) listener();
}
export function useWebLanguage() {
  return useSyncExternalStore((listener) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  }, webLanguage);
}
export function t(
  source: string,
  valuesOrEnglish?: Record<string, unknown> | string,
) {
  return translate(
    source,
    language,
    typeof valuesOrEnglish === "object" ? valuesOrEnglish : {},
    typeof valuesOrEnglish === "string" ? valuesOrEnglish : undefined,
  );
}
