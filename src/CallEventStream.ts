import { Platform } from "react-native";
import type {
  CallEventSnapshot,
  CallEventStreamOptions,
  CallEventStreamSubscription,
  OrderedCallEvent,
} from "./Calls.types";
import NativeModule from "./ExpoCallKitTelecomModule";

interface Batch {
  consumerID: string;
  epoch: string;
  events: OrderedCallEvent[];
}
interface Attachment extends Batch, CallEventSnapshot {}

/**
 * One acknowledged, ordered stream per iOS app bridge. Attach once near the app
 * root and distribute events to your media/UI adapters. Native work continues
 * through JS reloads. History survives bridge replacement within this process;
 * it does not survive app termination. Delivery may repeat after interruption.
 * Legacy listeners remain independent and have per-name ordering only.
 * Handlers run serially: dispatch cancellable media work promptly rather than
 * awaiting an unbounded connection, so later end events can be handled.
 */
export function subscribeCallEvents(
  options: CallEventStreamOptions,
): CallEventStreamSubscription {
  if (Platform.OS !== "ios") {
    throw new Error("The acknowledged call event stream currently requires iOS");
  }
  const consumerID = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const terminalCalls = new Set<string>();
  let closed = false;
  let initialized = false;
  let epoch = "";
  let processed = 0;
  let pending: OrderedCallEvent[] = [];
  let tail: Promise<void> = Promise.resolve();

  function rememberTerminalCalls(events: OrderedCallEvent[]) {
    for (const event of events) {
      if (event.name === "onCallEnded" || event.name === "onCallReportedEnded" || event.name === "onCallSessionRemoved") {
        terminalCalls.add(event.body.id);
      }
    }
  }

  async function acknowledge(revision: number) {
    if (closed) return;
    const accepted: boolean = await NativeModule.acknowledgeCallEvents(
      consumerID,
      epoch,
      revision,
    );
    if (!accepted) throw new Error("Call event stream was replaced by another consumer");
  }
  async function deliver(events: OrderedCallEvent[]) {
    for (const original of events.sort((a, b) => a.revision - b.revision)) {
      if (closed) return;
      if (original.epoch !== epoch) throw new Error("Call event process epoch changed");
      if (original.revision <= processed) continue;
      let event = original;
      if (event.name === "onCallAnswered" && (terminalCalls.has(event.body.id) ||
          (event.body.deadlineUnixMS !== undefined && event.body.deadlineUnixMS <= Date.now()))) {
        event = { ...event, expired: true };
      }
      await options.onEvent(event);
      if (closed) return;
      processed = event.revision;
      await acknowledge(processed);
    }
  }
  function fail(error: unknown) {
    if (closed) return;
    closed = true;
    listener.remove();
    void NativeModule.detachCallEventStream(consumerID).catch(() => {});
    try { options.onError?.(error); } catch { /* Error reporting cannot restart delivery. */ }
  }
  const listener = NativeModule.addListener("onCallEventStream", (batch: Batch) => {
    if (closed || batch.consumerID !== consumerID) return;
    rememberTerminalCalls(batch.events);
    if (!initialized) { pending.push(...batch.events); return; }
    tail = tail.then(() => deliver(batch.events));
    // Keep failure observable without creating an unhandled rejection from a
    // native event callback. The unacknowledged event remains in native history.
    void tail.catch(fail);
  });
  const ready: Promise<void> = Promise.resolve().then(() => NativeModule.attachCallEventStream(consumerID)).then(
    async (attachment: Attachment) => {
      if (closed) {
        await NativeModule.detachCallEventStream(consumerID);
        return;
      }
      epoch = attachment.epoch;
      rememberTerminalCalls(attachment.events);
      if (!attachment.replayComplete) {
        if (!options.onReplayGap) throw new Error("Call history overflowed; handle onReplayGap to accept its snapshot");
        await options.onReplayGap(attachment);
      }
      await deliver(attachment.events);
      if (closed) return;
      await options.onSnapshot?.(attachment);
      if (closed) return;
      await acknowledge(attachment.revision);
      initialized = true;
      // Live events emitted while attach/replay was in flight are serialized
      // after the snapshot; duplicate revisions are discarded by deliver().
      const buffered = pending;
      pending = [];
      tail = deliver(buffered);
      await tail;
    },
  );
  void ready.catch(fail);
  return {
    ready,
    remove() {
      if (closed) return;
      closed = true;
      listener.remove();
      void NativeModule.detachCallEventStream(consumerID).catch((error: unknown) => {
        try { options.onError?.(error); } catch { /* Delivery is already detached. */ }
      });
    },
  };
}
