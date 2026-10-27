import { expect, test } from "bun:test";
import { buildEvent } from "../../example/server/lib/event";
import type { Caller } from "../../src/conversations/types";

test("the example FCM producer supplies the required typed Caller child", () => {
  const phone: Caller = buildEvent({
    eventId: "event",
    serverCallId: "call",
    phoneNumber: "+12025550123",
    displayName: "Caller",
  }).caller;
  expect(phone).toEqual({
    handleType: "phone",
    value: "+12025550123",
    displayName: "Caller",
  });
  expect(buildEvent({ callerId: "host-account" }).caller).toEqual({
    handleType: "generic",
    value: "host-account",
    displayName: "Test Caller",
  });
  expect(() => buildEvent({ phoneNumber: "2025550123" })).toThrow();
});
