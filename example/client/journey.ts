import {
  endConversation,
  getConversations,
  joinConversation,
  mergeConversations,
  reportConversation,
  reportIncomingConversation,
  setConversationHandler,
  setHeld,
  startConversation,
  type ConversationErrorCode,
  type ConversationId,
} from "expo-callkit-telecom";
import { File, Paths } from "expo-file-system";
import { Platform } from "react-native";

/** The example supplies local signaling without connecting a media room. */
export async function runJourney(): Promise<readonly string[]> {
  const result = new File(Paths.document, "conversation-journey.json");
  if (result.exists) result.delete();
  const steps: string[] = [];
  const rejections: ConversationErrorCode[] = [];
  const owned = new Set<ConversationId>();
  async function expectRejection(
    code: ConversationErrorCode,
    operation: () => Promise<unknown>
  ) {
    try {
      await operation();
    } catch (error: unknown) {
      if (
        !(error instanceof Error) ||
        !("code" in error) ||
        error.code !== code ||
        error.message !== "Conversation operation failed"
      ) {
        throw new Error(
          `Expected typed ${code} rejection, received ${String(error)}`
        );
      }
      rejections.push(code);
      return;
    }
    throw new Error(`Expected ${code} rejection, command succeeded`);
  }
  async function expect(id: ConversationId, expected: string) {
    const state = (await getConversations()).find((call) => call.id === id);
    if (state?.status !== expected)
      throw new Error(`Expected ${expected}, received ${state?.status}`);
    steps.push(expected);
  }
  try {
    const stamp = Date.now().toString();
    const incoming = await reportIncomingConversation({
      eventId: `example-event-${stamp}`,
      serverCallId: `example-call-${stamp}`,
      caller: {
        handleType: "generic",
        value: "example-caller",
        displayName: "Example caller",
      },
      hasVideo: false,
    });
    if (incoming.status !== "reported")
      throw new Error(`Incoming report: ${incoming.status}`);
    const id = incoming.id;
    owned.add(id);
    await expect(id, "ringing");
    if (Platform.OS === "android") {
      await expectRejection("invalid_arguments", () =>
        startConversation({ id: `outgoing-${Date.now()}`, to: { id: "" }, video: false })
      );
      await expectRejection("unsupported", () => mergeConversations(id, id));
    }
    await joinConversation(id);
    await expect(id, "connecting");
    await reportConversation(id, { type: "connected" });
    await expect(id, "connected");
    await setHeld(id, true);
    await expect(id, "held");
    await setHeld(id, false);
    await expect(id, "connected");
    await endConversation(id);
    owned.delete(id);
    if ((await getConversations()).some((call) => call.id === id))
      throw new Error("Incoming call still present");
    steps.push("incoming-ended");
    if (Platform.OS === "android")
      await expectRejection("unknown_conversation", () => joinConversation(id));
    const outgoing = await startConversation({ id: `outgoing-${Date.now()}`,
      to: { id: "example-callee" },
      video: false,
    });
    owned.add(outgoing);
    await expect(outgoing, "dialing");
    await reportConversation(outgoing, { type: "connecting" });
    await expect(outgoing, "connecting");
    await reportConversation(outgoing, { type: "connected" });
    await expect(outgoing, "connected");
    await endConversation(outgoing);
    owned.delete(outgoing);
    if ((await getConversations()).some((call) => call.id === outgoing))
      throw new Error("Outgoing call still present");
    steps.push("outgoing-ended");
    if (Platform.OS === "android") {
      setConversationHandler({
        start: () => new Promise<void>(() => {}),
      });
      try {
        await expectRejection("deadline_exceeded", () =>
          startConversation({ id: `outgoing-${Date.now()}`,
            to: { id: "example-timeout-callee" },
            video: false,
          })
        );
      } finally {
        setConversationHandler({ start() {} });
      }
      if (
        (await getConversations()).some((call) =>
          call.members.some((member) => member.id === "example-timeout-callee")
        )
      ) {
        throw new Error("Timed-out outgoing call still present");
      }
    }
    result.write(
      JSON.stringify({
        passed: true,
        steps,
        rejections,
        transport: "expo-native-bridge",
      })
    );
    return steps;
  } catch (error) {
    result.write(
      JSON.stringify({ passed: false, steps, error: String(error) })
    );
    throw error;
  } finally {
    for (const id of owned)
      await reportConversation(id, { type: "ended", reason: "failed" }).catch(
        () => {}
      );
  }
}
