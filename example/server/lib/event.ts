import { randomUUID } from "node:crypto";
import type { Caller } from "../../../src/conversations/types";

export interface IncomingCallEvent {
  eventId: string;
  serverCallId: string;
  hasVideo: boolean;
  startedAt: string;
  caller: Caller;
  metadata?: Record<string, unknown>;
}

export interface BuildEventArgs {
  eventId?: string;
  serverCallId?: string;
  hasVideo?: boolean;
  callerId?: string;
  displayName?: string;
  phoneNumber?: string;
  metadata?: Record<string, unknown>;
}

export function buildEvent(args: BuildEventArgs = {}): IncomingCallEvent {
  if (
    args.phoneNumber !== undefined &&
    !/^\+[1-9]\d{1,14}$/.test(args.phoneNumber)
  ) {
    throw new Error(
      `phoneNumber must be E.164 (e.g. +14155551234), got: ${args.phoneNumber}`
    );
  }
  return {
    eventId: args.eventId ?? randomUUID(),
    serverCallId: args.serverCallId ?? `test-${Date.now()}`,
    hasVideo: args.hasVideo ?? false,
    startedAt: new Date().toISOString(),
    caller: {
      handleType: args.phoneNumber ? "phone" : "generic",
      value: args.phoneNumber ?? args.callerId ?? "test-caller",
      displayName: args.displayName ?? "Test Caller",
    },
    ...(args.metadata ? { metadata: args.metadata } : {}),
  };
}
