import { describe, expect, test } from "bun:test";
import { RevisionStore } from "../../src/conversations/runtime";

describe("native snapshot ordering", () => {
  test("stale hydration cannot replace a newer event or mutate the cache", () => {
    const store = new RevisionStore<readonly { id: string }[]>([]);
    store.accept(2, [{ id: "new" }]);
    const value = store.read();
    store.accept(1, [{ id: "old" }]);
    expect(store.read()).toBe(value);
    expect(Object.isFrozen(store.read()[0])).toBe(true);
    expect(() => store.accept(-1, [])).toThrow();
  });
  test("valid state recovers failed hydration", () => {
    const store = new RevisionStore<readonly string[]>([]);
    store.fail(new Error("read failed"));
    expect(() => store.read()).toThrow();
    store.accept(0, []);
    expect(store.read()).toEqual([]);
  });
  test("unchanged frozen values retain their identity across revisions", () => {
    const store = new RevisionStore<readonly { id: string }[]>([]);
    store.accept(1, [{ id: "stable" }]);
    const first = store.read()[0];
    store.accept(2, [first!]);
    expect(store.read()[0]).toBe(first);
  });
});
