import { subscribeCallEvents, type CallSession } from "expo-callkit-telecom";
import { useEffect, useState } from "react";
import { Platform } from "react-native";

/** Demonstrates bridge detachment without ending the native call. */
export function useOrderedCallSession() {
  const [session, setSession] = useState<CallSession | null>(null);
  const [paused, setPaused] = useState(false);
  const [history, setHistory] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (paused || Platform.OS !== "ios") return;
    const subscription = subscribeCallEvents({
      onEvent(event) {
        setHistory((previous) => [...previous, `${event.revision}:${event.name}${event.expired ? ":expired" : ""}`].slice(-32));
        if (event.name === "onCallSessionAdded" || event.name === "onCallSessionUpdated") {
          setSession(event.body.session.status === "ended" ? null : event.body.session);
        } else if (event.name === "onCallSessionRemoved" || event.name === "onCallEnded" || event.name === "onCallReportedEnded") {
          setSession((current) => current?.id === event.body.id ? null : current);
        }
      },
      onSnapshot(snapshot) {
        setSession(snapshot.sessions.find((candidate) => candidate.status !== "ended") ?? null);
      },
      onReplayGap(snapshot) {
        setError(`History gap through revision ${snapshot.droppedThroughRevision}; session snapshot restored`);
      },
      onError(cause) { setError(String(cause)); },
    });
    return () => subscription.remove();
  }, [paused]);
  return { session, paused, setPaused, history, error };
}
