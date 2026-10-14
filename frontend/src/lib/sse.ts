import { API_URL, getAccessToken, refreshSession } from "./api";

export interface StreamHandle {
  close(): void;
}

/**
 * Subscribes to a Server-Sent Events endpoint with the bearer token in a header (EventSource
 * cannot send headers). Reconnects with backoff and refreshes the token when it expires.
 */
export function subscribeSSE(
  path: string,
  onEvent: (event: string, data: unknown) => void,
  onStateChange?: (connected: boolean) => void,
): StreamHandle {
  let closed = false;
  let controller: AbortController | null = null;
  let attempt = 0;

  const connect = async () => {
    if (closed) return;
    controller = new AbortController();
    try {
      const token = getAccessToken();
      const res = await fetch(`${API_URL}${path}`, {
        headers: { Accept: "text/event-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        credentials: "include",
        signal: controller.signal,
        cache: "no-store",
      });
      if (res.status === 401) {
        const session = await refreshSession();
        if (!session) throw new Error("unauthenticated");
        throw new Error("retry");
      }
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
      attempt = 0;
      onStateChange?.(true);

      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        let sep: number;
        while ((sep = buffer.search(/\r?\n\r?\n/)) >= 0) {
          const raw = buffer.slice(0, sep);
          buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, "");
          dispatch(raw);
        }
      }
      throw new Error("stream ended");
    } catch (e) {
      onStateChange?.(false);
      if (closed || (e instanceof Error && e.message === "unauthenticated")) return;
      const delay = Math.min(30_000, 1000 * 2 ** attempt++);
      setTimeout(connect, delay);
    }
  };

  const dispatch = (raw: string) => {
    let event = "message";
    const data: string[] = [];
    for (const line of raw.split(/\r?\n/)) {
      if (line.startsWith(":")) continue; // heartbeat comment
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (!data.length) return;
    try {
      onEvent(event, JSON.parse(data.join("\n")));
    } catch {
      onEvent(event, data.join("\n"));
    }
  };

  connect();
  return {
    close() {
      closed = true;
      controller?.abort();
    },
  };
}
