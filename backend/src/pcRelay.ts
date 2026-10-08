import { json, now } from "./core";

type Pending = {
  socket: WebSocket;
  resolve: (response: Response) => void;
  timer: ReturnType<typeof setTimeout>;
};

/** Ephemeral transport only: license records and secrets are never stored here. */
export class PcLicenseRelay {
  private pending = new Map<string, Pending>();
  constructor(private state: DurableObjectState) {
    state.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair("ping", "pong"),
    );
  }
  private socket() {
    return this.state
      .getWebSockets()
      .find(
        (socket) =>
          socket.readyState === 1 &&
          socket.deserializeAttachment()?.ready === true,
      );
  }
  private unavailable() {
    return json({ state: "SERVER_ERROR", server_time: now() }, 503);
  }
  private failSocket(socket: WebSocket) {
    for (const [id, pending] of this.pending) {
      if (pending.socket !== socket) continue;
      clearTimeout(pending.timer);
      this.pending.delete(id);
      pending.resolve(this.unavailable());
    }
  }
  async fetch(req: Request) {
    const path = new URL(req.url).pathname;
    if (path === "/connect") {
      if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket")
        return this.unavailable();
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      pair[1].serializeAttachment({ ready: false });
      for (const old of this.state.getWebSockets()) {
        if (old === pair[1]) continue;
        this.failSocket(old);
        old.close(1000, "owner reconnected");
      }
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    const socket = this.socket();
    if (path === "/status")
      return json({
        connected: !!socket,
        source_sha: socket?.deserializeAttachment()?.source_sha ?? null,
      });
    if (
      path !== "/request" ||
      req.method !== "POST" ||
      !socket ||
      this.pending.size >= 128
    )
      return this.unavailable();
    const text = await req.text();
    if (new TextEncoder().encode(text).length > 49152)
      return this.unavailable();
    const request = JSON.parse(text);
    const id = crypto.randomUUID();
    return await new Promise<Response>((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(this.unavailable());
      }, 2500);
      this.pending.set(id, { socket, timer, resolve });
      try {
        socket.send(JSON.stringify({ type: "request", id, request }));
      } catch {
        this.failSocket(socket);
      }
    });
  }
  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer) {
    if (
      typeof message !== "string" ||
      new TextEncoder().encode(message).length > 270336
    ) {
      this.failSocket(socket);
      socket.close(1009, "invalid relay message");
      return;
    }
    let value: any;
    try {
      value = JSON.parse(message);
    } catch {
      return;
    }
    if (value.type === "ready" && /^[0-9a-f]{40}$/.test(value.source_sha)) {
      socket.serializeAttachment({ ready: true, source_sha: value.source_sha });
      socket.send(
        JSON.stringify({ type: "ready_ack", source_sha: value.source_sha }),
      );
      return;
    }
    if (value.type !== "response" || typeof value.id !== "string") return;
    const pending = this.pending.get(value.id);
    if (!pending || pending.socket !== socket) return;
    clearTimeout(pending.timer);
    this.pending.delete(value.id);
    if (
      !Number.isInteger(value.status) ||
      value.status < 200 ||
      value.status > 599 ||
      typeof value.body !== "string" ||
      new TextEncoder().encode(value.body).length > 262144
    ) {
      pending.resolve(this.unavailable());
      return;
    }
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    };
    if (
      typeof value.request_id === "string" &&
      /^REQ-[0-9a-f-]{36}$/.test(value.request_id)
    )
      headers["X-Request-Id"] = value.request_id;
    try {
      pending.resolve(
        new Response(
          [204, 205, 304].includes(value.status) ? null : value.body,
          {
            status: value.status,
            headers,
          },
        ),
      );
    } catch {
      pending.resolve(this.unavailable());
    }
  }
  webSocketClose(socket: WebSocket) {
    this.failSocket(socket);
  }
  webSocketError(socket: WebSocket) {
    this.failSocket(socket);
  }
}
