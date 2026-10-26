import { useEffect, useRef, useSyncExternalStore } from "react";
import bridge from "./ExpoCallKitTelecomModule";
import {
  HandlerDispatcher,
  RevisionStore,
  reconcile,
} from "./conversations/runtime";
import type {
  AudioRoutes,
  Conversation,
  ConversationEventMap,
  ConversationHandler,
  ConversationId,
  PushToken,
} from "./conversations/types";

class Runtime {
  readonly conversations = new RevisionStore<readonly Conversation[]>([]);
  readonly routes = new RevisionStore<AudioRoutes>({
    routes: [],
    current: null,
  });
  readonly token = new RevisionStore<PushToken | null>(null);
  readonly handler = new HandlerDispatcher(bridge);
  constructor() {
    bridge.addListener("conversationsChanged", (event) =>
      this.update(event.revision, event.conversations)
    );
    bridge.addListener("audioRoutesChanged", (event) =>
      this.routes.accept(event.revision, event)
    );
    bridge.addListener("pushTokenUpdated", (event) =>
      this.token.accept(event.revision, event.token)
    );
    bridge.addListener("handlerAction", (event) =>
      this.handler.dispatch(event)
    );
    bridge.addListener("actionCancelled", (event) =>
      this.handler.cancel(event.actionID, event.generation, event.reason)
    );
    const snapshot = bridge.snapshot;
    this.update(snapshot.revision, snapshot.conversations);
    void this.read().catch((error: unknown) => this.conversations.fail(error));
    void bridge
      .getAudioRoutes()
      .then((event) => this.routes.accept(event.revision, event))
      .catch((error: unknown) => this.routes.fail(error));
    void bridge
      .getPushToken()
      .then((event) => this.token.accept(event.revision, event.token))
      .catch((error: unknown) => this.token.fail(error));
  }
  private update(
    revision: number,
    conversations: readonly Conversation[]
  ): void {
    this.conversations.accept(
      revision,
      reconcile(this.conversations.peek(), conversations)
    );
  }
  async read(): Promise<readonly Conversation[]> {
    const event = await bridge.getConversations();
    this.update(event.revision, event.conversations);
    return this.conversations.read();
  }
}
const registry = globalThis as typeof globalThis & {
  __expoConversationsRuntime?: Runtime;
};
const runtime = (registry.__expoConversationsRuntime ??= new Runtime());

/** Complete current conversation list, including native cold-launch state. @category State */
export const getConversations = (): Promise<readonly Conversation[]> =>
  runtime.read();
/** Subscribe to immutable system state. @category Hooks */
export function useConversations(): readonly Conversation[] {
  return useSyncExternalStore(
    runtime.conversations.subscribe,
    runtime.conversations.read
  );
}
/** Subscribe to one stable conversation object. @category Hooks */
export function useConversation(id: ConversationId): Conversation | undefined {
  return useSyncExternalStore(runtime.conversations.subscribe, () =>
    runtime.conversations.read().find((conversation) => conversation.id === id)
  );
}
/** Native audio endpoints and the actual current output. @category Hooks */
export function useAudioRoutes(): AudioRoutes {
  return useSyncExternalStore(runtime.routes.subscribe, runtime.routes.read);
}
/** Current native push registration, or null. @category Hooks */
export function usePushToken(): PushToken | null {
  return useSyncExternalStore(runtime.token.subscribe, runtime.token.read);
}
/** Register at module scope before React mounts; null detaches and cancels old work. @category Handler */
export function setConversationHandler(
  handler: ConversationHandler | null
): void {
  runtime.handler.set(handler);
}
/** Lifecycle signals that cannot be derived from state. @category Hooks */
export function useConversationEvent<Name extends keyof ConversationEventMap>(
  name: Name,
  listener: (event: ConversationEventMap[Name]) => void
): void {
  const callback = useRef(listener);
  callback.current = listener;
  useEffect(() => {
    if (name === "pushTokenUpdated")
      return runtime.token.subscribe(() =>
        callback.current(runtime.token.read() as ConversationEventMap[Name])
      );
    const subscription = bridge.addListener("conversationEvent", (event) => {
      if (event.type === name)
        callback.current(event.payload as ConversationEventMap[Name]);
    });
    return () => subscription.remove();
  }, [name]);
}
/** App → system commands. @category Commands */
export const startConversation = bridge.startConversation.bind(bridge);
export const joinConversation = bridge.joinConversation.bind(bridge);
export const endConversation = bridge.endConversation.bind(bridge);
export const setHeld = bridge.setHeld.bind(bridge);
export const setMuted = bridge.setMuted.bind(bridge);
export const playTones = bridge.playTones.bind(bridge);
export const mergeConversations = bridge.mergeConversations.bind(bridge);
/** Transport → system reports. @category Reports */
export const reportIncomingConversation =
  bridge.reportIncomingConversation.bind(bridge);
export const reportConversation = bridge.reportConversation.bind(bridge);
/** Request an available native endpoint. @category Audio */
export const selectAudioRoute = bridge.selectAudioRoute.bind(bridge);
