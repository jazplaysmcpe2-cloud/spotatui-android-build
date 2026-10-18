#!/usr/bin/env python3
"""Apply guarded Android loopback diagnostics to the pinned upstream source."""
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(relative_path: str, old: str, new: str, description: str) -> None:
    path = root / relative_path
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{description}: expected exactly one match in {relative_path}, found {count}; refusing a blind patch."
        )
    path.write_text(text.replace(old, new, 1))


# Native module interface: let the JS layer relay only relevant Rust log lines to logcat.
replace_once(
    "mobile/app/modules/spotatui-core/src/SpotatuiCoreModule.ts",
    "  setItem(key: string, value: string): void;",
    "  setItem(key: string, value: string): void;\n  /** Filtered tail of the in-process Rust core log, for startup diagnostics. */\n  getDiagnosticTail(): string;",
    "Native diagnostic interface",
)

replace_once(
    "mobile/app/modules/spotatui-core/android/src/main/java/expo/modules/spotatuicore/SpotatuiCoreModule.kt",
    '    Function("setItem") { key: String, value: String -> SessionStorage.items[key] = value }',
    '''    Function("setItem") { key: String, value: String -> SessionStorage.items[key] = value }

    Function("getDiagnosticTail") {
      val context = appContext.reactContext ?: throw Exceptions.ReactContextLost()
      val logDirectory = java.io.File(context.cacheDir, "spotatui_logs")
      val latestLog = logDirectory.listFiles()
        ?.filter { it.isFile && it.name.startsWith("spotatuilog") }
        ?.maxByOrNull { it.lastModified() }
      if (latestLog == null) {
        "No Spotatui core log file found."
      } else {
        runCatching {
          latestLog.useLines { lines ->
            lines
              .filter { line ->
                line.contains("GUI:") ||
                  line.contains("[ERROR]") ||
                  line.contains("[WARN]") ||
                  line.contains("startup", ignoreCase = true) ||
                  line.contains("panic", ignoreCase = true)
              }
              .toList()
              .takeLast(120)
              .joinToString(System.lineSeparator())
          }
        }.getOrElse { cause ->
          "Unable to read Spotatui diagnostics: ${cause.javaClass.simpleName}"
        }
      }
    }''',
    "Native diagnostic log reader",
)

core_path = "mobile/app/src/core.ts"
core = (root / core_path).read_text()
old_url = "  const url = await SpotatuiCore.start();\n  const [, port, code] = /:(\\d+)\\/#code=(.+)$/.exec(url) ?? [];"
new_url = """  const url = await SpotatuiCore.start();
  const [, port, code] = /:(\\d+)\\/#code=(.+)$/.exec(url) ?? [];
  if (!port) {
    throw new Error("Spotatui core returned an invalid local connection URL.");
  }
  const baseUrl = "http://127.0.0.1:" + port;
  try {
    const response = await fetch(baseUrl + "/", {
      method: "GET",
      headers: { Accept: "text/html" },
    });
    const body = await response.text();
    console.info(
      "[Spotatui] Local core HTTP preflight: status=" +
        response.status +
        ", bytes=" +
        body.length,
    );
    if (!response.ok) throw new Error("HTTP " + response.status);
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    let diagnostics = "No Rust logs available.";
    try {
      diagnostics = SpotatuiCore.getDiagnosticTail();
    } catch {}
    console.error("[Spotatui] Local core HTTP preflight failed: " + reason, diagnostics);
    throw new Error("Spotatui could not reach its local core over HTTP: " + reason);
  }"""
if core.count(old_url) != 1:
    raise SystemExit(f"Core URL extraction: expected one match, found {core.count(old_url)}.")
core = core.replace(old_url, new_url, 1)

start_token = "      const socket = new WebSocket("
start = core.find(start_token)
end_token = "      return socket;"
end = core.find(end_token, start)
if start < 0 or end < 0 or core.count(start_token) != 1 or core.count(end_token) != 1:
    raise SystemExit("Core WebSocket factory no longer matches the expected upstream shape; refusing a blind patch.")
new_socket = """      const wsUrl = "ws://127.0.0.1:" + port + "/ws";
      let receivedFirstFrame = false;
      const NativeWebSocket = WebSocket as unknown as {
        new (
          url: string,
          protocols: string | string[],
          options: { headers: Record<string, string> },
        ): WebSocket;
      };
      const socket = new NativeWebSocket(wsUrl, protocol, { headers: { origin: baseUrl } });
      socket.onopen = () => console.info("[Spotatui] Local core WebSocket opened");
      socket.onerror = (event) => {
        if (!receivedFirstFrame) {
          let diagnostics = "No Rust logs available.";
          try {
            diagnostics = SpotatuiCore.getDiagnosticTail();
          } catch {}
          console.error("[Spotatui] Local core WebSocket error before first frame", event, diagnostics);
        }
      };
      socket.onmessage = (event) => {
        receivedFirstFrame = true;
        handlers.message(String(event.data));
      };
      socket.onclose = (event) => {
        if (!receivedFirstFrame) {
          let diagnostics = "No Rust logs available.";
          try {
            diagnostics = SpotatuiCore.getDiagnosticTail();
          } catch {}
          console.error(
            "[Spotatui] Local core WebSocket closed before first frame: code=" +
              event.code +
              ", clean=" +
              event.wasClean +
              ", reason=" +
              (event.reason || "(none)"),
            diagnostics,
          );
        }
        handlers.close();
      };
      return socket;"""
core = core[:start] + new_socket + core[end + len(end_token):]
(root / core_path).write_text(core)

# Server-side messages omit the launch code and session token by design.
server_path = "src/gui/server.rs"
replace_once(
    server_path,
    "  if !is_allowed(&head, port) {",
    '''  if !is_allowed(&head, port) {
    log::warn!(
      "GUI: refused request: method={} host={:?} origin={:?} fetch_site={:?} upgrade={}",
      head.method, head.host, head.origin, head.fetch_site, head.upgrade
    );''',
    "Server Host/Origin refusal log",
)
replace_once(
    server_path,
    '          log::debug!("GUI: connection ended: {e}");',
    '          log::warn!("GUI: connection ended: {e:#}");',
    "Server connection error log",
)
replace_once(
    server_path,
    '        _ => {\n          let mut refusal = ErrorResponse::new(Some("forbidden".to_string()));',
    '''        _ => {
          log::warn!("GUI: refused WebSocket handshake: missing or invalid launch/session protocol");
          let mut refusal = ErrorResponse::new(Some("forbidden".to_string()));''',
    "Server WebSocket admission refusal log",
)
replace_once(
    server_path,
    '  let token = matches!(admission, Some(Admission::Fresh)).then(|| server.access.token.clone());',
    '''  let fresh_session = matches!(admission, Some(Admission::Fresh));
  log::info!("GUI: WebSocket handshake accepted (fresh_session={fresh_session})");
  let token = fresh_session.then(|| server.access.token.clone());''',
    "Server successful WebSocket handshake log",
)

print("Applied guarded Android loopback preflight, explicit Origin, WebSocket events, and filtered core diagnostics.")
