import { registerVoIPPush, useVoIPPushToken } from "expo-callkit-telecom";
import { useEffect } from "react";
import { Button, Platform, ScrollView, StyleSheet, Text } from "react-native";
import {
  SafeAreaProvider,
  SafeAreaView,
} from "react-native-safe-area-context";

import { ActionsCard } from "./components/ActionsCard";
import { AudioSessionCard } from "./components/AudioSessionCard";
import { EventLogCard } from "./components/EventLogCard";
import { SessionCard } from "./components/SessionCard";
import { useAllEvents } from "./hooks/useAllEvents";
import { useAudioSession } from "./hooks/useAudioSession";
import { useCallSession } from "./hooks/useCallSession";
import { useConnectCall } from "./hooks/useConnectCall";
import { useEventLog } from "./hooks/useEventLog";
import { useOrderedCallSession } from "./hooks/useOrderedCallSession";
import { Card } from "./components/Card";

export default function App() {
  const { lines, append, clear } = useEventLog();
  const legacySession = useCallSession();
  const ordered = useOrderedCallSession();
  const session = Platform.OS === "ios" ? ordered.session : legacySession;
  const audio = useAudioSession();
  const { canConnect, connect, failConnect } = useConnectCall();
  const voip = useVoIPPushToken();
  useAllEvents(append);

  useEffect(() => {
    registerVoIPPush();
  }, []);

  useEffect(() => {
    if (voip) console.log("VoIP device token:", voip.token);
  }, [voip]);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <ScrollView contentContainerStyle={styles.scroll}>
          <Text style={styles.header}>expo-callkit-telecom · example</Text>
          <SessionCard session={session} />
          <AudioSessionCard audio={audio} />
          <ActionsCard
            session={session}
            audio={audio}
            canConnect={canConnect}
            onConnect={connect}
            onFailConnect={failConnect}
            onError={append}
          />
          <EventLogCard lines={lines} onClear={clear} />
          {Platform.OS === "ios" && (
            <Card title="Ordered native events">
              <Button title={ordered.paused ? "Resume event stream" : "Pause event stream"}
                onPress={() => ordered.setPaused(!ordered.paused)} />
              <Text testID="ordered-event-history">{ordered.history.join("\n")}</Text>
              {ordered.error && <Text>{ordered.error}</Text>}
            </Card>
          )}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#eee" },
  scroll: { padding: 16 },
  header: { fontSize: 22, marginBottom: 16, fontWeight: "600" },
});
