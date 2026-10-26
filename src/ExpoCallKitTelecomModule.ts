import { requireNativeModule } from "expo";

import { commandError } from "./conversations/errors";
import type { NativeTransport } from "./conversations/transport";

const native = requireNativeModule<NativeTransport>("ExpoCallKitTelecom");
const commands = new Set<PropertyKey>([
  "getConversations",
  "getAudioRoutes",
  "getPushToken",
  "startConversation",
  "joinConversation",
  "endConversation",
  "setHeld",
  "setMuted",
  "playTones",
  "mergeConversations",
  "reportConversation",
  "reportIncomingConversation",
  "selectAudioRoute",
]);
export default new Proxy(native, {
  get(target, key) {
    const member: unknown = Reflect.get(target, key, target);
    if (typeof member !== "function") return member;
    if (!commands.has(key)) return member.bind(target);
    return async (...arguments_: unknown[]) => {
      try {
        return await Reflect.apply(member, target, arguments_);
      } catch (error: unknown) {
        throw commandError(error);
      }
    };
  },
});
