import { defineConfig } from "vite";
export default defineConfig({
  build: {
    outDir: "../app/src/main/assets/phone-remote",
    emptyOutDir: true,
    target: "es2020",
    assetsInlineLimit: 0,
  },
  base: "/",
});
