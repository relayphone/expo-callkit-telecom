import {
  endConversation, joinConversation, reportConversation, reportIncomingConversation,
  selectAudioRoute, setConversationHandler, setHeld, setMuted, startConversation,
  useAudioRoutes, useConversations, usePushToken,
} from "expo-callkit-telecom";
import { useEffect, useRef, useState } from "react";
import { Button, Linking, PermissionsAndroid, Platform, ScrollView, StyleSheet, Text, View } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { runJourney } from "./journey";

// Attach before React mounts. The example accepts local signaling without a media server.
setConversationHandler({ join() {}, start() {}, end() {} });

async function permissions() {
  if (Platform.OS === "android") await PermissionsAndroid.requestMultiple([
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  ]);
}
export default function App() {
  const calls = useConversations();
  const audio = useAudioRoutes();
  const token = usePushToken();
  const [message, setMessage] = useState("Ready");
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  async function perform(work: () => Promise<unknown>) {
    if (running.current) return;
    running.current = true; setBusy(true);
    try { await permissions(); await work(); setMessage("Completed"); }
    catch (error) { setMessage(String(error)); }
    finally { running.current = false; setBusy(false); }
  }
  useEffect(() => {
    const run = (url: string | null) => {
      if (url === "conversations-example://journey") void perform(runJourney);
    };
    void Linking.getInitialURL().then(run);
    const subscription = Linking.addEventListener("url", ({ url }) => run(url));
    return () => subscription.remove();
  }, []);
  return <SafeAreaProvider><SafeAreaView style={styles.screen}>
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.title}>Conversations v2</Text>
      <Text>Local signaling example. No media room is connected.</Text>
      <Text>Push registration: {token ? "registered" : "none"}</Text>
      <Text accessibilityLiveRegion="polite">{message}</Text>
      <Button title="Run incoming and outgoing journey" disabled={busy} onPress={() => void perform(runJourney)} />
      <Button title="Report incoming" disabled={busy} onPress={() => void perform(async () => {
        const stamp = Date.now().toString();
        const result = await reportIncomingConversation({ eventId: stamp, serverCallId: `manual-${stamp}`,
          caller: { handleType: "generic", value: "caller", displayName: "Example caller" }, hasVideo: false });
        if (result.status !== "reported") throw new Error(result.status);
      })} />
      <Button title="Start outgoing" disabled={busy} onPress={() => void perform(async () => {
        const id = await startConversation({ to: { id: "callee" }, video: false });
        await reportConversation(id, { type: "connected" });
      })} />
      {calls.map((call) => <View key={call.id} style={styles.card}>
        <Text>{call.members[0]?.displayName ?? call.members[0]?.id} · {call.status}</Text>
        {call.status === "ringing" && <Button title="Answer" disabled={busy} onPress={() => void perform(async () => {
          await joinConversation(call.id); await reportConversation(call.id, { type: "connected" });
        })} />}
        {(call.status === "connected" || call.status === "held") && <Button title={call.status === "held" ? "Resume" : "Hold"}
          disabled={busy} onPress={() => void perform(() => setHeld(call.id, call.status !== "held"))} />}
        <Button title={call.isMuted ? "Unmute" : "Mute"} disabled={busy}
          onPress={() => void perform(() => setMuted(call.id, !call.isMuted))} />
        <Button title="End" disabled={busy} onPress={() => void perform(() => endConversation(call.id))} />
      </View>)}
      <Text>Audio output: {audio.current?.name ?? "none"}</Text>
      {audio.routes.map((route) => <Button key={route.id} title={route.name} disabled={busy}
        onPress={() => void perform(() => selectAudioRoute(route.id))} />)}
    </ScrollView>
  </SafeAreaView></SafeAreaProvider>;
}
const styles = StyleSheet.create({
  screen: { flex: 1 }, content: { padding: 20, gap: 12 },
  title: { fontSize: 24, fontWeight: "600" }, card: { padding: 12, borderWidth: 1, borderColor: "#ccc" },
});
