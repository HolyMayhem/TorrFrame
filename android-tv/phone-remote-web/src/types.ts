export interface Metadata {
  provider: string;
  id: string;
  title: string;
  originalTitle: string;
  year: number | null;
  kind: "Movie" | "Tv";
  overview: string;
  poster: string;
  backdrop: string;
  rating: number;
  genres: string;
  runtime: number;
}
export interface VideoFile {
  id: number;
  path: string;
  length: number;
}
export interface LibraryItem {
  hash: string;
  title: string;
  sourceTitle: string;
  addedAt: number;
  metadata: Metadata | null;
  position: number;
  duration: number;
  lastFileId: number;
  files: VideoFile[];
}
export interface Settings {
  revision: number;
  jackettEnabled: boolean;
  jackettUrl: string;
  jackettKeyConfigured: boolean;
  metadataOrder: string[];
  aniListEnabled: boolean;
  language: string;
  resume: boolean;
  audioLanguage: string;
  subtitleLanguage: string;
  uiLanguage: string;
  updateRepository: string;
  torrServerUrl: string;
  useLocalServer: boolean;
  port: number;
  startOnBoot: boolean;
  lanAccess: boolean;
  uiTheme: string;
  uiAccent: string;
  uiScalePercent: number;
  animationsEnabled: boolean;
  uiSoundsEnabled: boolean;
  showNavigationHints: boolean;
  playerMode: string;
  externalPlayer: string;
  builtInPlayer: PlayerPreferences;
  torrentSearch: SearchOptions;
  catalogNetwork: CatalogNetworkSettings;
}
export interface CatalogNetworkSettings {
  route: "Direct" | "Auto" | "Proxy";
  source: "Tmdb" | "Cub" | "Auto";
  showFavorites: boolean;
  showWatchLater: boolean;
  apiProxy: string;
  imageMirrors: string[];
  cubUrl: string;
  cubTokenConfigured: boolean;
  cubToken?: string;
}
export interface Server {
  local: boolean;
  connection: "Connecting" | "Connected" | "Unavailable" | "Error";
  version: string;
  process: string;
  error: string;
}
export interface Playback {
  active: boolean;
  identity: string;
  title: string;
  playing: boolean;
  buffering: boolean;
  playWhenReady: boolean;
  ended: boolean;
  seekable: boolean;
  position: number;
  duration: number;
  error: boolean;
  tracks: {
    id: string;
    type: "audio" | "subtitle";
    label: string;
    selected: boolean;
  }[];
}
export interface Updates {
  busy: boolean;
  message: string;
  serverVersion: string;
  availableVersion: string;
  downloaded: boolean;
}
export interface Operation {
  id: string;
  requestId: string;
  type: string;
  status: "running" | "completed" | "failed" | "cancelled";
  error: string;
  conflict: boolean;
  result: unknown;
}
export interface Snapshot {
  v: 1;
  epoch: string;
  sequence: number;
  csrf: string;
  device: { name: string; appVersion: string };
  settings: Settings;
  library: LibraryItem[];
  server: Server;
  playback: Playback;
  updates: Updates;
  operations: Operation[];
  searchProviders: { id: string; name: string }[];
  catalogRevision?: number;
}
export interface RemoteEvent {
  v: number;
  epoch: string;
  sequence: number;
  type: string;
  data?: unknown;
}
export type Command =
  | "catalog.browse"
  | "catalog.details"
  | "catalog.related"
  | "catalog.lists"
  | "catalog.toggle"
  | "settings.players"
  | "library.magnet"
  | "server.lan"
  | "search"
  | "library.add"
  | "library.sync"
  | "library.remove"
  | "library.files"
  | "library.play"
  | "metadata.refresh"
  | "metadata.reset"
  | "metadata.rename"
  | "metadata.search"
  | "metadata.select"
  | "settings.update"
  | "settings.testJackett"
  | "settings.clearMetadataCache"
  | "server.connect"
  | "server.boot"
  | "server.start"
  | "server.stop"
  | "server.restart"
  | "server.check"
  | "server.diagnose"
  | "engine.get"
  | "engine.save"
  | "engine.reset"
  | "playback.command"
  | "updates.server"
  | "updates.check"
  | "updates.download"
  | "updates.install";
export interface TorrentResult {
  handle: string;
  title: string;
  size: number;
  seeds: number;
  peers: number;
  tracker: string;
  metadata: Metadata | null;
}
export interface MetadataChoice {
  handle: string;
  metadata: Metadata;
}
export interface Engine {
  address: string;
  version: string;
  canReset: boolean;
  fields: {
    key: string;
    label: string;
    hint: string;
    labelRu: string;
    labelEn: string;
    hintRu: string;
    hintEn: string;
    value: string;
    boolean: boolean;
    min: number;
    max: number;
  }[];
}

export interface PlayerPreferences {
  speed: number;
  aspect: string;
  decoder: string;
  matchFrameRate: boolean;
  skipSilence: boolean;
  subtitleSize: number;
  subtitleColor: string;
  subtitleBackground: boolean;
  subtitleBold: boolean;
  embeddedSubtitleStyle: boolean;
}
export interface SearchOptions {
  sources?: string[] | null;
  resolutions: string[];
  formats: string[];
  minBytes: number;
  maxBytes: number;
  minSeeds: number;
  hideZero: boolean;
  timeout: number;
  titleMode: string;
}
export interface CatalogItem {
  id: number;
  mediaType: "Movie" | "Tv";
  key: string;
  title: string;
  originalTitle: string;
  poster: string;
  backdrop: string;
  year: number | null;
  rating: number;
  favorite?: boolean;
  watchLater?: boolean;
  partial?: boolean;
  stale?: boolean;
  overview?: string;
  genres?: string[];
  runtime?: number;
  cast?: string[];
  directors?: string[];
  seasons?: number;
  episodes?: number;
  status?: string;
}
export interface CatalogPage {
  items: CatalogItem[];
  page: number;
  totalPages: number;
  partial?: boolean;
  stale?: boolean;
}
export interface SearchPreset {
  query: string;
  filter?: string;
  originalTitle?: string;
  year?: number | null;
  tmdbId?: number;
}
