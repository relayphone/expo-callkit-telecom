import {
  endConversation, getConversations, joinConversation,
  reportConversation, reportIncomingConversation, setHeld,
  startConversation, type ConversationId,
} from "expo-callkit-telecom";
import { File, Paths } from "expo-file-system";

/** The example supplies local signaling without connecting a media room. */
export async function runJourney(): Promise<readonly string[]> {
  const result = new File(Paths.document, "conversation-journey.json");
  if (result.exists) result.delete();
  const steps: string[] = [];
  const owned = new Set<ConversationId>();
  async function expect(id: ConversationId, expected: string) {
    const state = (await getConversations()).find((call) => call.id === id);
    if (state?.status !== expected) throw new Error(`Expected ${expected}, received ${state?.status}`);
    steps.push(expected);
  }
  try {
    const stamp = Date.now().toString();
    const incoming = await reportIncomingConversation({
      eventId: `example-event-${stamp}`, serverCallId: `example-call-${stamp}`,
      caller: { id: "example-caller", displayName: "Example caller" }, hasVideo: false,
    });
    if (incoming.status !== "reported") throw new Error(`Incoming report: ${incoming.status}`);
    const id = incoming.id;
    owned.add(id); await expect(id, "ringing");
    await joinConversation(id); await expect(id, "connecting");
    await reportConversation(id, { type: "connected" }); await expect(id, "connected");
    await setHeld(id, true); await expect(id, "held");
    await setHeld(id, false); await expect(id, "connected");
    await endConversation(id); owned.delete(id);
    if ((await getConversations()).some((call) => call.id === id)) throw new Error("Incoming call still present");
    steps.push("incoming-ended");
    const outgoing = await startConversation({ to: { id: "example-callee" }, video: false });
    owned.add(outgoing); await expect(outgoing, "dialing");
    await reportConversation(outgoing, { type: "connecting" }); await expect(outgoing, "connecting");
    await reportConversation(outgoing, { type: "connected" }); await expect(outgoing, "connected");
    await endConversation(outgoing); owned.delete(outgoing);
    if ((await getConversations()).some((call) => call.id === outgoing)) throw new Error("Outgoing call still present");
    steps.push("outgoing-ended");
    result.write(JSON.stringify({ passed: true, steps, transport: "expo-native-bridge" }));
    return steps;
  } catch (error) {
    result.write(JSON.stringify({ passed: false, steps, error: String(error) }));
    throw error;
  } finally {
    for (const id of owned) await reportConversation(id, { type: "ended", reason: "failed" }).catch(() => {});
  }
}
