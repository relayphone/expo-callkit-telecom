#!/usr/bin/env python3
"""Validate every required DND study pair without treating missing observations as false."""

import argparse
import itertools
import json
import math
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


SENDERS = {"any": 0, "contacts": 1, "starred": 2}
CALLERS = ("starred", "contact", "noncontact")
CALLS_CATEGORY = 8
REPEAT_CATEGORY = 16
CASE_PATTERN = re.compile(r"dnd-study-(platform|native)-(any|contacts|starred)-(false|true)-(starred|contact|noncontact)-([123])\.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, name, minimum=0):
    require(type(value) is int and value >= minimum, f"{name}: nonnegative integer required")
    return value


def number(value, name, minimum=0):
    require(type(value) in (int, float) and math.isfinite(value) and value >= minimum,
            f"{name}: finite number >= {minimum} required")
    return value


def object_value(value, name):
    require(isinstance(value, dict), f"{name}: object required")
    return value


def unique_keys(items):
    result = {}
    for key, value in items:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def policy(value, calls, repeat, senders=None):
    value = object_value(value, "policy")
    require(value.get("interruptionFilter") == 2 and type(value["interruptionFilter"]) is int,
            "policy is not priority DND")
    categories = integer(value.get("priorityCategories"), "priorityCategories")
    sender_value = integer(value.get("priorityCallSenders"), "priorityCallSenders")
    require(sender_value in SENDERS.values(), "invalid priority sender readback")
    require(value.get("calls") is calls and bool(categories & CALLS_CATEGORY) is calls,
            "calls policy readback mismatch")
    require(value.get("repeat") is repeat and bool(categories & REPEAT_CATEGORY) is repeat,
            "repeat policy readback mismatch")
    if senders is not None:
        require(sender_value == SENDERS[senders], "sender policy readback mismatch")


def expected_allowed(senders, caller, repeat, ordinal):
    membership = senders == "any" or caller == "starred" or (senders == "contacts" and caller == "contact")
    return membership or (ordinal == "B" and repeat)


def check_case(path, lane, run):
    match = CASE_PATTERN.fullmatch(path.name)
    require(match is not None, "unexpected study result filename")
    file_lane, senders, repeated, caller, index = match.groups()
    repeat, index = repeated == "true", int(index)
    require(file_lane == lane, "result filename belongs to another lane")
    data = json.loads(path.read_text(), object_pairs_hook=unique_keys,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON constant")))
    data = object_value(data, "result")
    require(type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1, "unsupported schemaVersion")
    require(type(data.get("apiLevel")) is int and data["apiLevel"] == 35
            and type(data.get("targetSdk")) is int and data["targetSdk"] == 35, "API35/target35 evidence required")
    require(data.get("passed") is True, "study invocation did not pass")
    require(data.get("lane") == lane and data.get("run") == run, "lane/run mismatch")
    require(data.get("senders") == senders and data.get("caller") == caller and data.get("repeat") is repeat,
            "case labels disagree with filename")
    require(type(data.get("sampleIndex")) is int and data["sampleIndex"] == index, "sampleIndex mismatch")
    phone = data.get("callerE164")
    require(isinstance(phone, str) and re.fullmatch(r"\+[1-9][0-9]{6,14}", phone) is not None, "invalid E164 fixture identity")
    uri = data.get("callerUri")
    require(isinstance(uri, str), "callerUri required")
    parsed = urlsplit(uri)
    require(parsed.scheme == "tel" and not parsed.netloc and not parsed.query and not parsed.fragment
            and unquote(parsed.path) == phone, "caller URI does not decode to its E164 identity")
    host_id = data.get("hostContactId")
    require(isinstance(host_id, str) and bool(host_id) and host_id.strip() == host_id
            and not host_id.isdecimal() and host_id != phone, "opaque host contact key required")
    lookup = object_value(data.get("phoneLookup"), "phoneLookup")
    require(lookup.get("contact") is (caller != "noncontact") and lookup.get("starred") is (caller == "starred"),
            "actual PhoneLookup membership disagrees with caller class")
    permissions = object_value(data.get("permissions"), "permissions")
    require(all(permissions.get(key) is True for key in
                ("readContacts", "writeContacts", "notificationListenerConnected", "notificationPolicyAccess")),
            "required contacts/listener/policy access not observed")
    preflight = object_value(data.get("preflight"), "preflight")
    require(preflight.get("callFilterAllowed") is False, "repeat-history preflight did not prove a fresh identity")
    policy(preflight.get("policy"), calls=False, repeat=True)
    require(data.get("repeatHistoryAfterA") is True, "attempt A did not independently populate repeat history")
    require(type(data.get("pairWaitMs")) is int and data["pairWaitMs"] == 2000, "two-second pair wait missing")
    calls = data.get("calls")
    require(isinstance(calls, list) and len(calls) == 2, "exactly two A/B reports required")
    ranking_absent = 0
    for ordinal, call in zip(("A", "B"), calls):
        call = object_value(call, "call observation")
        allowed = expected_allowed(senders, caller, repeat, ordinal)
        require(call.get("ordinal") == ordinal, "A/B call order mismatch")
        require(call.get("expectedAllowed") is allowed and call.get("callFilterAllowed") is allowed,
                "public call-filter decision disagrees with the independent sender/repeat expectation")
        policy(call.get("policy"), calls=True, repeat=repeat, senders=senders)
        require(call.get("coreAdmitted") is True, "Core admission not observed")
        require(call.get("channelBypass") is False and call.get("channelSoundConfigured") is True,
                "call channel bypass/sound evidence mismatch")
        require(integer(call.get("channelImportance"), "channelImportance") <= 5, "invalid call channel importance")
        filtered_native = lane == "native" and not allowed
        ringing = call.get("coreRingingObserved")
        require(ringing is True or (filtered_native and "coreRingingObserved" in call and ringing is None),
                "Core ringing observation missing outside a transient filtered native call")
        require("notificationRanking" in call, "notification ranking field missing")
        ranking = call["notificationRanking"]
        if ranking is None:
            require(filtered_native, "notification ranking missing outside a transient filtered native call")
            ranking_absent += 1
        else:
            ranking = object_value(ranking, "notificationRanking")
            require(ranking.get("matchesFilter") is allowed and ranking.get("soundConfigured") is True
                    and ranking.get("category") == "call", "actual call notification ranking/sound mismatch")
            require(ranking.get("personUri") == uri and ranking.get("personKey") == host_id,
                    "notification Person identity disagrees with the typed caller")
            require(ranking.get("channelBypass") is False, "call channel bypasses DND")
            require(integer(ranking.get("importance"), "notification importance") <= 5, "invalid notification importance")
        require(call.get("ringtonePlaybackObserved") is allowed, "actual ringtone observation disagrees with policy")
        number(call.get("playbackObservationMs"), "playbackObservationMs", 1000)
        volume = integer(call.get("ringVolume"), "ringVolume")
        if allowed:
            require(volume > 0, "permitted call has zero ring volume")
        require(integer(call.get("ringerMode"), "ringerMode") in (0, 1, 2), "invalid ringer mode")
        require(all(key in call for key in ("nativeOutcome", "nativeHookCount", "deduplicated")),
                "native outcome observation fields missing")
        if lane == "platform":
            require(all(call[key] is None for key in ("nativeOutcome", "nativeHookCount", "deduplicated")),
                    "platform lane unexpectedly used native filtering")
        else:
            outcome = object_value(call.get("nativeOutcome"), "nativeOutcome")
            require(outcome.get("status") == ("reported" if allowed else "filtered"), "typed native outcome mismatch")
            require("reason" in outcome and outcome["reason"] == (None if allowed else "doNotDisturb"),
                    "typed native reason mismatch")
            require(type(call.get("nativeHookCount")) is int and call["nativeHookCount"] == 1
                    and call.get("deduplicated") is True, "native hook/deduplication evidence mismatch")
        for key in ("coreCallsAfterCleanup", "engineCallsAfterCleanup", "ownNotificationsAfterCleanup"):
            require(integer(call.get(key), key) == 0, "call or notification remains after cleanup")
        started = integer(call.get("startedNanos"), "startedNanos", 1)
        require(integer(call.get("cleanupNanos"), "cleanupNanos", 1) >= started, "cleanup preceded call start")
    pair_gap = number(data.get("pairGapMs"), "pairGapMs", 2000)
    measured_gap = (calls[1]["startedNanos"] - calls[0]["startedNanos"]) / 1_000_000
    cleanup_gap = number(data.get("cleanupToSecondMs"), "cleanupToSecondMs", 2000)
    measured_cleanup_gap = (calls[1]["startedNanos"] - calls[0]["cleanupNanos"]) / 1_000_000
    require(abs(pair_gap - measured_gap) <= 1 and abs(cleanup_gap - measured_cleanup_gap) <= 1,
            "A/B interval disagrees with the monotonic observation timestamps")
    require(measured_cleanup_gap >= 2000, "attempt B started before the required post-cleanup wait")
    cleanup = object_value(data.get("contactCleanup"), "contactCleanup")
    require(integer(cleanup.get("ownedRowsRemaining"), "ownedRowsRemaining") == 0, "owned contact rows remain")
    return {"phone": phone, "hostId": host_id, "rankingAbsent": ranking_absent}


def check_directory(directory, lane, run):
    expected = {f'dnd-study-{lane}-{sender}-{str(repeat).lower()}-{caller}-{sample}.json'
                for sender, repeat, caller, sample in itertools.product(SENDERS, (False, True), CALLERS, range(1, 4))}
    actual = {path.name for path in directory.glob("*.json")}
    require(actual == expected, f"all 54 pairs required; missing={len(expected - actual)}, unexpected={len(actual - expected)}")
    cases = [check_case(directory / filename, lane, run) for filename in sorted(expected)]
    require(len({case["phone"] for case in cases}) == 54, "caller identity reused across study cases")
    require(len({case["hostId"] for case in cases}) == 54, "host contact key reused across study cases")
    return sum(case["rankingAbsent"] for case in cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--case-file", type=Path)
    source.add_argument("--input-dir", type=Path)
    parser.add_argument("--lane", choices=("platform", "native"), required=True)
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    try:
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.run) is not None, "invalid study run label")
        if args.case_file is not None:
            check_case(args.case_file, args.lane, args.run)
            print(f"DND study pair checked: {args.case_file.stem}")
        else:
            absent = check_directory(args.input_dir, args.lane, args.run)
            print(f"DND study {args.lane}: all 54 pairs / 108 reports checked; {absent} transient filtered-native rankings absent")
    except (OSError, ValueError, TypeError, KeyError) as error:
        parser.exit(1, f"DND study check: {error}\n")


if __name__ == "__main__":
    main()
