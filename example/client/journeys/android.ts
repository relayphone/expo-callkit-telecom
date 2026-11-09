import { requireNativeModule } from "expo";
import * as api from "expo-callkit-telecom";
import type {
  Conversation,
  ConversationEventMap,
  ConversationsSnapshot,
  Subscription,
} from "expo-callkit-telecom";
import { File, Paths } from "expo-file-system";

import { driveAndroidJourney, newAndroidResults } from "./android-drive";

type LifecycleEvent = {
  [Name in Exclude<keyof ConversationEventMap, "pushTokenUpdated">]: {
    type: Name;
    payload: ConversationEventMap[Name];
  };
}[Exclude<keyof ConversationEventMap, "pushTokenUpdated">];
interface Events {
  conversationsChanged: ConversationsSnapshot;
  conversationEvent: LifecycleEvent;
  handlerAction: { kind: string; conversation: Conversation; origin: string };
  actionCancelled: { reason: string };
}
interface Bridge {
  addListener<Name extends keyof Events>(
    name: Name,
    listener: (event: Events[Name]) => void
  ): Subscription;
}

/** Test-only deep-link journey; injection and system observation live in the instrumentation APK. */
export async function runAndroidJourney(runID: string): Promise<void> {
  if (!/^[a-z0-9-]{1,80}$/.test(runID))
    throw new Error("Invalid journey run ID");
  const result = newAndroidResults(runID);
  const output = new File(Paths.document, "android-journey.json");
  const acknowledgement = new File(Paths.document, "android-journey-ack.json");
  const bridge = requireNativeModule<Bridge>("VoIPConversations");
  const previous = new Set<string>();
  const ended = new Set<string>();
  const subscriptions = [
    bridge.addListener("conversationsChanged", ({ conversations }) => {
      for (const call of conversations) {
        if (!call.id.startsWith(`${runID}-`)) continue;
        if (call.status === "ended" && !ended.has(call.id)) {
          ended.add(call.id);
          result.terminals.push({
            id: call.id,
            reason: call.ended?.reason ?? "missing",
            ...(call.ended?.reason === "failed" && call.ended.failure
              ? { failure: call.ended.failure }
              : {}),
          });
          result.lifecycle.push({ id: call.id, kind: "ended" });
        }
        previous.add(call.id);
      }
      for (const id of previous)
        if (!conversations.some((call) => call.id === id)) {
          result.lifecycle.push({ id, kind: "removed" });
          previous.delete(id);
        }
    }),
    bridge.addListener("handlerAction", (event) =>
      result.deliveries.push({
        kind: event.kind,
        id: event.conversation.id,
        origin: event.origin,
      })
    ),
    bridge.addListener("actionCancelled", (event) =>
      result.cancelled.push(event.reason)
    ),
    bridge.addListener("conversationEvent", (event) => {
      if (event.type === "actionPerformed") {
        const { conversationId: id, origin, action } = event.payload;
        result.actions.push({
          id,
          origin,
          kind: action.kind,
          ...(action.kind === "hold" ? { held: action.held } : {}),
        });
        if (action.kind === "end")
          result.lifecycle.push({ id, kind: "performedEnd" });
      } else if (event.type === "incomingConversationOutcome") {
        const { serverCallId: id, eventId, outcome } = event.payload;
        result.outcomes.push({
          id,
          eventId,
          status: outcome.status,
          ...(outcome.status === "filtered" ? { reason: outcome.reason } : {}),
        });
      } else if (event.type === "answeredElsewhere") {
        result.elsewhere.push(event.payload);
        result.lifecycle.push({
          id: event.payload.conversationId,
          kind: "elsewhere",
        });
      }
    }),
  ];
  try {
    await driveAndroidJourney(result, api, {
      save: () => output.write(JSON.stringify(result)),
      acknowledged: async (sequence) => {
        if (!acknowledgement.exists) return false;
        const ack: unknown = JSON.parse(await acknowledgement.text());
        return (
          !!ack &&
          typeof ack === "object" &&
          "runID" in ack &&
          ack.runID === runID &&
          "sequence" in ack &&
          ack.sequence === sequence
        );
      },
    });
  } finally {
    subscriptions.forEach((subscription) => subscription.remove());
    api.setConversationHandler({ start() {} });
  }
}
