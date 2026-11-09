import type { ConversationId } from "expo-callkit-telecom";

import type {
  AndroidResults,
  AndroidStage,
  JourneyApi,
} from "./android-results.ts";

export function newAndroidResults(runID: string): AndroidResults {
  return {
    schemaVersion: 1,
    runID,
    sequence: 0,
    stage: "incoming:ringing",
    previewJoin: "unsupported",
    snapshots: [],
    handlers: [],
    deliveries: [],
    actions: [],
    outcomes: [],
    elsewhere: [],
    cancelled: [],
    terminals: [],
    lifecycle: [],
    completed: [],
  };
}

/** Pause at every observed state until the target-app instrumentation has captured system evidence. */
export async function driveAndroidJourney(
  result: AndroidResults,
  transport: JourneyApi,
  io: { save(): void; acknowledged(sequence: number): Promise<boolean> },
  timing = {
    now: () => Date.now(),
    pause: () => new Promise<void>((resolve) => setTimeout(resolve, 50)),
  }
): Promise<void> {
  async function bounded<T>(promise: Promise<T>): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () => reject(new Error("Native command timed out")),
            30_000
          );
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
  const api: JourneyApi = {
    ...transport,
    getConversations: () => bounded(transport.getConversations()),
    joinConversation: (id) => bounded(transport.joinConversation(id)),
    setHeld: (id, held) => bounded(transport.setHeld(id, held)),
    startConversation: (options) =>
      bounded(transport.startConversation(options)),
    reportConversation: (id, report) =>
      bounded(transport.reportConversation(id, report)),
    endConversation: (id) => bounded(transport.endConversation(id)),
  };
  const id = (name: string) => `${result.runID}-${name}`;
  async function until(label: string, read: () => Promise<boolean>) {
    const deadline = timing.now() + 30_000;
    while (timing.now() < deadline) {
      if (result.cancelled.length)
        throw new Error("Unexpected action cancellation");
      if (await bounded(read())) return;
      await timing.pause();
    }
    throw new Error(`Timed out waiting for ${label}`);
  }
  async function checkpoint(
    step: AndroidStage,
    status: AndroidResults["snapshots"][number]["status"]
  ) {
    result.stage = step;
    result.sequence++;
    io.save();
    await until(`checkpoint ${result.sequence}`, () =>
      io.acknowledged(result.sequence)
    );
    const name = step.split(":")[0]!;
    let observed: ConversationId | undefined;
    await until(step, async () => {
      const call = (await api.getConversations()).find(
        (call) => call.id === id(name)
      );
      if ((call?.status ?? "absent") !== status) return false;
      if (
        call &&
        call.direction !== (name === "outgoing" ? "outgoing" : "incoming")
      )
        throw new Error("Direction changed");
      observed = call?.id;
      result.snapshots.push({
        step,
        id: id(name),
        status,
        direction: name === "outgoing" ? "outgoing" : "incoming",
      });
      return true;
    });
    return observed;
  }
  api.setConversationHandler({
    start(call, context) {
      result.handlers.push({
        kind: "start",
        id: call.id,
        origin: context.origin,
      });
    },
  });
  try {
    const incoming = await checkpoint("incoming:ringing", "ringing");
    if (!incoming) throw new Error("Missing incoming identity");
    await api.joinConversation(incoming);
    await api.reportConversation(incoming, { type: "connected" });
    await checkpoint("incoming:connected", "connected");
    await api.setHeld(incoming, true);
    await checkpoint("incoming:held", "held");
    await api.endConversation(incoming);
    await checkpoint("incoming:removed", "absent");
    result.completed.push("incoming");
    const outgoing = await api.startConversation({
      id: id("outgoing"),
      to: { id: id("outgoing") },
      video: false,
    });
    if (outgoing !== id("outgoing"))
      throw new Error("Outgoing identity changed");
    await api.reportConversation(outgoing, { type: "connected" });
    await checkpoint("outgoing:connected", "connected");
    await api.endConversation(outgoing);
    await checkpoint("outgoing:removed", "absent");
    result.completed.push("outgoing");
    await checkpoint("elsewhere:ringing", "ringing");
    await checkpoint("elsewhere:removed", "absent");
    result.completed.push("answeredElsewhere");
    await checkpoint("filtered:removed", "absent");
    result.completed.push("filtered");
    await until(
      "native event delivery",
      async () =>
        result.actions.length >= 5 &&
        result.outcomes.length >= 3 &&
        result.elsewhere.length >= 1
    );
    result.stage = "passed";
    io.save();
  } catch (error) {
    result.failure = String(error);
    result.stage = "failed";
    io.save();
    throw error;
  } finally {
    api.setConversationHandler(null);
    if (result.stage === "failed")
      for (const call of await api.getConversations().catch(() => []))
        if (call.id.startsWith(`${result.runID}-`))
          await api
            .reportConversation(call.id, { type: "ended", reason: "failed" })
            .catch(() => {});
  }
}
