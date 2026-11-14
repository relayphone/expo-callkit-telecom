"""CI-only TLS signaling proxy, credential endpoint and real LiveKit PCM publisher."""
import asyncio
import base64
import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import ssl
import struct
import sys
import time
from fractions import Fraction
from typing import NamedTuple

from aiohttp import ClientSession, WSMsgType, web
from livekit import api, rtc
from aiortc import AudioStreamTrack, RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaBlackhole
from av import AudioFrame

directory = Path(sys.argv[1]) if __name__ == "__main__" and sys.argv[1] != "--analyze" else None
settings = json.loads((directory / "settings.json").read_text()) if directory else {}
bots = {}
fetches = {}
states = {}
lock = asyncio.Lock()
raw_peers = {}
closed_negotiations = set()
terminal_gates = {}
terminal_times = {}
uplinks = {}
downlinks = {}
uplink_tasks = set()


class TonesV2Signal:
    """Canonical Float32 source, quantized to PCM16 LE with ties toward positive infinity."""
    def __init__(self, identity, rate):
        if not identity or len(identity) > 4096 or not 8000 <= rate <= 96000 or rate % 50:
            raise ValueError("Invalid tones-v2 source")
        self.rate = rate
        tones = (650, 950, 1350, 1850)
        hash_value = 2166136261
        for byte in identity.encode("utf-8"):
            hash_value = ((hash_value ^ byte) * 16777619) & 0xffffffff
        self.frequencies = tones + tuple(tones[(hash_value >> shift) & 3] for shift in range(0, 16, 2))

    def pcm(self, count, offset=0, *, emission=True):
        if count < 0 or count > self.rate * 60 or offset < 0:
            raise ValueError("Invalid PCM range")
        slot = self.rate // 50 * 3
        pre = math.floor(self.rate * 3 / 20 + .5)
        post = pre + slot * 12
        cycle = self.rate // 50 * 51
        values = []
        for index in range(offset, offset + count):
            position = index % cycle if emission else index % (slot * 12)
            in_marker = not emission or pre <= position < post
            if in_marker:
                marker_position = position - pre if emission else position
                local = marker_position % slot
                frequency = self.frequencies[marker_position // slot]
                edge = min(local, slot - local)
            else:
                local = position if position < pre else position - post
                frequency = 400
                edge = local if position < pre else cycle - position
            fade = min(1, edge / (self.rate / 200))
            value = .2 * (.5 * (1 - math.cos(math.pi * fade))) * math.sin(2 * math.pi * frequency * local / self.rate)
            normalized = struct.unpack("<f", struct.pack("<f", value))[0]
            values.append(math.floor(normalized * 32767 + .5))
        return struct.pack("<" + str(count) + "h", *values)


class MarkerPath(NamedTuple):
    movement: int
    confidence_sum: float
    minimum: float
    starts: tuple[int, ...]
    confidences: tuple[float, ...]


def prefer_marker_path(left: MarkerPath, right: MarkerPath) -> bool:
    if left.movement != right.movement:
        return left.movement < right.movement
    if abs(left.confidence_sum - right.confidence_sum) > 1e-9:
        return left.confidence_sum > right.confidence_sum
    return left.starts < right.starts


class MarkerObservation:
    """Bounded real uplink PCM; tones-v2 matches SDK testing/marker.ts without relaxed slots."""
    next_generation = 0
    rate = 48000
    slot_samples = 2880

    def __init__(self, identity):
        MarkerObservation.next_generation += 1
        self.generation = MarkerObservation.next_generation
        self.identity = identity
        self.frequencies = TonesV2Signal(identity, self.rate).frequencies
        self.running = True
        self.error = None
        self.track = None
        self.saved = 0
        self.epoch = 0
        self.reset()

    def reset(self):
        self.epoch += 1
        self.frames = 0
        self.pcm = []
        self.analyzed = 0
        self.saved = 0
        self.analysis_pcm = []
        self.analysis_result = None
        self.detections = []
        self.cycles = []
        self.started_ns = time.monotonic_ns()
        self.arrivals = []
        self.stats = []
        self.stats_error = None
        self.diagnostics_overflow = False
        self.evidence = {"markers": 0, "profile": "tones-v2", "markerId": self.identity,
                         "sampleRate": self.rate, "slots": [], "minimumConfidence": None, "markersWithPostRoll": 0}

    @staticmethod
    def analyze(pcm, frequencies, rate=48000):
        # Complete-path tones-v2, ported from the SDK detector after the 0.5.0 release.
        # Cache overlapping slot scores; never alter, stitch or repair received PCM.
        if rate not in (8000, 48000) or len(frequencies) != 12:
            raise ValueError("Invalid analysis format")
        window, slot_samples = rate // 50, rate // 50 * 3
        length, cap = slot_samples * 12, slot_samples
        scores = {}
        shifts = [0] + [shift for distance in range(1, 16) for shift in (-distance, distance)]
        epsilon = 1e-12  # SDK tie rule only; the >=0.8 acceptance threshold is unchanged.
        def confidence(start, frequency):
            if start < window or start - window + slot_samples > len(pcm):
                return -1
            key = (start, frequency)
            if key in scores:
                return scores[key]
            coefficient = 2 * math.cos(2 * math.pi * frequency / rate)
            previous = before = energy = 0.0
            for index in range(start, start + window):
                value = pcm[index] / 32768
                current = value + coefficient * previous - before
                before, previous = previous, current
                energy += value * value
            result = 0 if energy < window * .0001 else min(1, max(0, 2 * (
                previous * previous + before * before - coefficient * previous * before) / (window * energy)))
            scores[key] = result
            return result
        def complete_path(anchor, first_score):
            paths = {0: MarkerPath(0, first_score, first_score, (anchor,), (first_score,))}
            for index, frequency in enumerate(frequencies[1:], 1):
                following = {}
                reach = 0 if frequency == frequencies[index - 1] else 15
                for previous_drift, prefix in paths.items():
                    for drift in range(max(-60, previous_drift - reach), min(60, previous_drift + reach) + 1):
                        start = anchor + index * slot_samples + drift * rate // 1000
                        value = confidence(start + window, frequency)
                        if value < .8:
                            continue
                        candidate = MarkerPath(prefix.movement + abs(drift - previous_drift),
                            prefix.confidence_sum + value, min(prefix.minimum, value),
                            prefix.starts + (start,), prefix.confidences + (value,))
                        incumbent = following.get(drift)
                        if incumbent is None or prefer_marker_path(candidate, incumbent):
                            following[drift] = candidate
                paths = following
                if not paths:
                    return None
            chosen = None
            for path in paths.values():
                if chosen is None or prefer_marker_path(path, chosen):
                    chosen = path
            return chosen
        best = None
        cap_rejected = None
        detections = []
        cycles = []
        offset = 0
        step = rate // 1000
        while offset + length <= len(pcm):
            starts = [offset]
            slots = [confidence(offset + window, frequencies[0])]
            if slots[0] < .8:
                offset += step
                continue
            search_end = offset + slot_samples
            # Fix the sync anchor before later slots: a failed cap must never slide slot zero.
            for alternative in range(offset + step, min(search_end, len(pcm) - length) + 1, step):
                score = confidence(alternative + window, frequencies[0])
                if score > slots[0] + epsilon:
                    starts[0], slots[0] = alternative, score
            offset = starts[0]
            path = complete_path(offset, slots[0])
            if path is not None:
                starts, slots = path.starts, path.confidences
            else:
                # Retain the previous failed-alignment diagnostics; they can never accept a marker.
                for index, frequency in enumerate(frequencies[1:], 1):
                    expected = starts[-1] + slot_samples
                    start, score = expected, -1
                    for ms in shifts:
                        alternative = expected + ms * rate // 1000
                        value = confidence(alternative + window, frequency)
                        if value > score + epsilon or score < .8 <= value:
                            start, score = alternative, value
                        if score >= 1 - epsilon:
                            break
                    starts.append(start)
                    slots.append(score)
            minimum = min(slots)
            item = (minimum, offset, slots, starts)
            complete = starts[-1] + slot_samples <= len(pcm)
            in_cap = all(abs(start - offset - index * slot_samples) <= cap for index, start in enumerate(starts))
            if complete:
                cycles.append(item)
            if not in_cap:
                if minimum >= .8 and (cap_rejected is None or minimum > cap_rejected[0] + epsilon):
                    cap_rejected = item
            if complete and in_cap and (best is None or minimum > best[0] + epsilon):
                best = item
            if path is not None:
                detections.append(item)
                offset = starts[-1] + slot_samples
            else:
                offset = search_end + step
        chosen = detections[0] if detections else best
        def receipt(item):
            minimum, offset, values, starts = item
            in_cap = all(abs(start - offset - index * slot_samples) <= cap for index, start in enumerate(starts))
            valid_steps = all(abs(starts[index] - starts[index - 1] - slot_samples) <=
                (0 if frequencies[index] == frequencies[index - 1] else rate * 15 // 1000) for index in range(1, 12))
            return {"offsetSamples": offset, "endSamples": starts[-1] + slot_samples,
                    "minimumConfidence": minimum,
                    "syncComplete": min(values[:4]) >= .8,
                    "insideDriftCap": in_cap, "pathValid": minimum >= .8 and in_cap and valid_steps,
                    "slots": [{"index": index, "frequencyHz": frequency, "confidence": score, "margin": score - .8,
                               "startSamples": start, "driftSamples": start - offset - index * slot_samples,
                               "driftMs": (start - offset - index * slot_samples) * 1000 / rate}
                              for index, (frequency, score, start) in enumerate(zip(frequencies, values, starts))]}
        return {"markers": len(detections), "detectionOffsets": [item[1] for item in detections], "minimumConfidence": chosen[0] if chosen else None,
                "offsetSamples": chosen[1] if chosen else None,
                "slots": receipt(chosen)["slots"] if chosen else [], "detections": [receipt(item) for item in detections],
                "cycles": [receipt(item) for item in cycles],
                "driftCapRejected": receipt(cap_rejected) if cap_rejected else None}

    async def measure(self):
        # Score any complete 720 ms window, including a short leak or the final partial second.
        # Keep observing throughout the phase, with only three seconds retained at once.
        if len(self.pcm) >= 34560 and self.frames > self.analyzed:
            epoch = self.epoch
            frames = self.frames
            pcm = self.pcm[:]
            result = await analysis_worker.analyze(pcm, self.frequencies)
            if epoch == self.epoch:
                self.analyzed = frames
                base = frames - len(pcm)
                self.analysis_pcm = pcm
                self.analysis_result = {**result, "windowStartSamples": base, "windowEndSamples": frames}
                for item in result["cycles"]:
                    absolute = {**item, "offsetSamples": base + item["offsetSamples"], "endSamples": base + item["endSamples"],
                                "slots": [{**slot, "startSamples": base + slot["startSamples"]} for slot in item["slots"]]}
                    previous = next((cycle for cycle in self.cycles if abs(cycle["offsetSamples"] - absolute["offsetSamples"]) < 2880), None)
                    if previous is None:
                        if len(self.cycles) >= 128:
                            raise ValueError("Phase cycle limit exceeded")
                        self.cycles.append(absolute)
                    elif absolute["minimumConfidence"] > previous["minimumConfidence"] + 1e-12:
                        previous.update(absolute)
                for item in result["detections"]:
                    start = base + item["offsetSamples"]
                    end = base + item["endSamples"]
                    if not any(start < previous["endSamples"] and end > previous["offsetSamples"] for previous in self.detections):
                        if len(self.detections) >= 32:
                            raise ValueError("Phase detection limit exceeded")
                        self.detections.append({**item, "offsetSamples": start, "endSamples": end,
                                                "slots": [{**slot, "startSamples": base + slot["startSamples"]} for slot in item["slots"]]})
                result["windowStartSamples"] = base
                if not self.evidence["markers"] or result["markers"]:
                    self.evidence.update(result)
                self.evidence["markers"] = len(self.detections)
                # The guarded detector can place onset 20 ms early; preserve the full 150 ms tail.
                self.evidence["markersWithPostRoll"] = sum(item["endSamples"] + 8160 <= frames for item in self.detections)
        def transport(item):
            before = next((sample for sample in reversed(self.stats) if sample["frame"] <= item["offsetSamples"] and sample["inbound"]), None)
            after = next((sample for sample in self.stats if sample.get("fromFrame", sample["frame"]) >= item["endSamples"] and sample["inbound"]), None)
            delta = None if before is None or after is None else {
                name: after["inbound"][0][name] - before["inbound"][0][name]
                if before["inbound"][0][name] is not None and after["inbound"][0][name] is not None else None
                for name in ("inserted_samples_for_deceleration", "removed_samples_for_acceleration", "concealed_samples", "packets_lost")}
            timeline = [] if before is None or after is None else self.stats[self.stats.index(before):self.stats.index(after) + 1]
            known = delta is not None and all(value is not None and value >= 0 for value in delta.values()) and all(
                sample["inbound"] and all(sample["inbound"][0][name] is not None for name in delta) for sample in timeline)
            # packets_lost can fall again after late recovery. Never erase a sampled loss spike.
            peak = None if before is None or after is None else {
                name: max((sample["inbound"][0][name] - before["inbound"][0][name]
                    for sample in timeline
                    if sample["inbound"] and sample["inbound"][0][name] is not None), default=0)
                if before["inbound"][0][name] is not None else None
                for name in ("concealed_samples", "packets_lost")}
            increases = {name: sum(max(0, second["inbound"][0][name] - first["inbound"][0][name])
                for first, second in zip(timeline, timeline[1:]) if first["inbound"] and second["inbound"] and
                first["inbound"][0][name] is not None and second["inbound"][0][name] is not None)
                for name in ("concealed_samples", "packets_lost")}
            impaired = any(value > 0 for value in increases.values()) or (
                peak is not None and any(value is not None and value > 0 for value in peak.values()))
            return {"beforeFrame": before["frame"] if before else None,
                    "afterFrame": after["frame"] if after else None, "delta": delta,
                    "maximumIncrease": peak,
                    "positiveIncreases": increases,
                    "status": "impaired" if impaired else "clean" if known else "unknown"}
        phase = [{**item, "receiveStats": transport(item)} for item in self.detections]
        self.evidence["phaseDetections"] = phase
        self.evidence["cycles"] = [{**item, "receiveStats": transport(item)} for item in self.cycles]
        self.evidence["cleanCyclesWithPostRoll"] = sum(item["endSamples"] + 8160 <= self.frames and
            item["receiveStats"]["status"] == "clean" for item in phase)
        self.evidence["firstCleanCycle"] = next((item for item in sorted(phase, key=lambda item: item["offsetSamples"])
            if item["endSamples"] + 8160 <= self.frames and item["receiveStats"]["status"] == "clean"), None)
        if directory is not None and self.identity.startswith("hooks-") and self.analyzed:
            evidence = directory / "media-evidence" / (hashlib.sha256(self.identity.encode()).hexdigest()[:16] + "-" + str(self.epoch))
            evidence.mkdir(parents=True, exist_ok=True)
            (evidence / "cycles.json").write_text(json.dumps(self.evidence))
            if self.saved < self.analyzed:
                (evidence / (str(self.analyzed) + ".pcm16le")).write_bytes(struct.pack("<" + str(len(self.analysis_pcm)) + "h", *self.analysis_pcm))
                (evidence / (str(self.analyzed) + ".json")).write_text(json.dumps(self.analysis_result))
            self.saved = self.analyzed
        return {**self.evidence, "receiveClock": "host.monotonic_ns", "arrivals": self.arrivals,
                "rtc": self.stats, "statsError": self.stats_error, "diagnosticsOverflow": self.diagnostics_overflow}

    @staticmethod
    def inbound_reports(reports):
        values = []
        for report in reports:
            if report.HasField("inbound_rtp"):
                item = report.inbound_rtp
                counters = {name: getattr(item.inbound, name) if item.inbound.HasField(name) else None
                            for name in ("total_samples_received", "concealed_samples", "silent_concealed_samples",
                                         "concealment_events", "inserted_samples_for_deceleration",
                                         "removed_samples_for_acceleration", "jitter_buffer_delay",
                                         "jitter_buffer_emitted_count", "packets_discarded")}
                counters.update({name: getattr(item.received, name) if item.received.HasField(name) else None
                                 for name in ("packets_received", "packets_lost", "jitter")})
                values.append(counters)
        return values

    async def poll_stats(self, track):
        while True:
            epoch = self.epoch
            first_frame = self.frames
            try:
                values = self.inbound_reports(await asyncio.wait_for(track.get_stats(), 2))
                if epoch == self.epoch:
                    if len(self.stats) < 128:
                        self.stats.append({"atNs": time.monotonic_ns() - self.started_ns, "fromFrame": first_frame,
                                           "frame": self.frames, "inbound": values})
                    else:
                        self.diagnostics_overflow = True
            except Exception as error:
                if epoch == self.epoch:
                    self.stats_error = type(error).__name__
            await asyncio.sleep(.25)

    async def receive(self, track):
        stream = None
        self.track = track
        stats_task = asyncio.create_task(self.poll_stats(track)) if track is not None and self.identity.startswith("hooks-") else None
        try:
            stream = rtc.AudioStream(track, sample_rate=self.rate, num_channels=1)
            async for event in stream:
                if event.frame.sample_rate != self.rate or event.frame.num_channels != 1:
                    raise ValueError("Uplink PCM format changed")
                values = memoryview(event.frame.data).cast("B").cast("h")
                if len(self.arrivals) < 4096:
                    self.arrivals.append([time.monotonic_ns() - self.started_ns, self.frames, len(values)])
                else:
                    self.diagnostics_overflow = True
                self.frames += len(values)
                self.pcm.extend(values)
                del self.pcm[:max(0, len(self.pcm) - self.rate * 3)]
        except Exception as error:
            self.error = type(error).__name__
        finally:
            self.running = False
            if stats_task:
                stats_task.cancel()
                await asyncio.gather(stats_task, return_exceptions=True)
            if stream is not None:
                await stream.aclose()


class AnalysisWorker:
    """CPU-heavy detection must not hold the GIL needed by real-time RTC callbacks."""
    def __init__(self):
        self.process = None
        self.lock = asyncio.Lock()

    async def close(self, *_):
        if self.process is not None:
            if self.process.returncode is None:
                self.process.kill()
            await asyncio.wait_for(self.process.wait(), 2)
            self.process = None

    async def analyze(self, pcm, frequencies):
        async with self.lock:
            try:
                if self.process is None:
                    self.process = await asyncio.create_subprocess_exec(
                        sys.executable, str(Path(__file__).resolve()), "--analyze",
                        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.DEVNULL)
                async def exchange():
                    header = json.dumps({"frames": len(pcm), "frequencies": frequencies}).encode() + b"\n"
                    self.process.stdin.write(header + struct.pack("<" + str(len(pcm)) + "h", *pcm))
                    await self.process.stdin.drain()
                    line = await self.process.stdout.readline()
                    if not line:
                        raise RuntimeError("Analyzer exited")
                    return json.loads(line)
                return await asyncio.wait_for(exchange(), 3)
            except BaseException:
                await self.close()
                raise


analysis_worker = AnalysisWorker()


def analyze_stdin():
    import signal
    signal.signal(signal.SIGALRM, lambda *_: __import__("os")._exit(124))
    signal.alarm(540)
    for line in sys.stdin.buffer:
        request = json.loads(line)
        count = request["frames"]
        if not 34560 <= count <= 144000 or len(request["frequencies"]) != 12:
            raise ValueError("Invalid analysis window")
        data = sys.stdin.buffer.read(count * 2)
        pcm = struct.unpack("<" + str(count) + "h", data)
        print(json.dumps(MarkerObservation.analyze(pcm, request["frequencies"])), flush=True)


class ToneTrack(AudioStreamTrack):
    def __init__(self):
        super().__init__()
        self.index = 0
        self.started = None

    async def recv(self):
        if self.started is None:
            self.started = asyncio.get_running_loop().time()
        await asyncio.sleep(max(0, self.started + self.index / 48000 - asyncio.get_running_loop().time()))
        frame = AudioFrame(format="s16", layout="mono", samples=960)
        frame.planes[0].update(struct.pack("<960h", *[int(6000 * math.sin(2 * math.pi * 440 * (self.index + i) / 48000)) for i in range(960)]))
        frame.sample_rate = 48000
        frame.pts = self.index
        frame.time_base = Fraction(1, 48000)
        self.index += 960
        return frame


def token(room, identity):
    return (api.AccessToken(settings["key"], settings["secret"])
            .with_identity(identity).with_ttl(datetime.timedelta(seconds=120))
            .with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True))
            .to_jwt())


async def publish(room_name):
    room = rtc.Room()

    @room.on("track_subscribed")
    def received(track, publication, participant):
        if track.kind == rtc.TrackKind.KIND_AUDIO and participant.identity.startswith("device-"):
            observation = MarkerObservation(room_name.removeprefix("native-ci-") + ":fromDevice")
            uplinks[room_name] = observation
            task = asyncio.create_task(observation.receive(track))
            uplink_tasks.add(task)
            task.add_done_callback(uplink_tasks.discard)
            # Consume receiver failures without logging private SDK exception details.
            task.add_done_callback(lambda finished: None if finished.cancelled() else finished.exception())

    await room.connect("ws://127.0.0.1:7880", token(room_name, "fixture-publisher"))
    source = rtc.AudioSource(48000, 1)
    track = rtc.LocalAudioTrack.create_audio_track("fixture-tone", source)
    await room.local_participant.publish_track(track,
        rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))

    signal = TonesV2Signal(room_name.removeprefix("native-ci-") + ":toDevice", 48000) if room_name.startswith("native-ci-hooks-") else None

    return room, asyncio.create_task(publish_frames(source, signal))


async def publish_frames(source, signal, clock=None, pause=asyncio.sleep, limit=None):
    clock = clock or asyncio.get_running_loop().time
    started = clock()
    index = 0
    while limit is None or index < limit * 480:
        pcm = signal.pcm(480, index) if signal else struct.pack("<480h", *[int(6000 * math.sin(2 * math.pi * 440 * (index + i) / 48000)) for i in range(480)])
        index += 480
        await source.capture_frame(rtc.AudioFrame(pcm, 48000, 1, 480))
        await pause(max(0, started + index / 48000 - clock()) if signal else .01)


async def credentials(server_id):
    if server_id.startswith("rtc-"):
        return {"provider": "webrtc", "url": "https://10.0.2.2:9443/fixture/webrtc/" + server_id,
                "token": settings["deviceAuth"], "expiresAt": int(time.time()) + 120, "iceServers": []}
    room_name = "native-ci-" + server_id
    async with lock:
        if room_name not in bots:
            bots[room_name] = await publish(room_name)
    identity = "device-" + server_id
    return {"provider": "livekit", "url": "wss://10.0.2.2:9443", "room": room_name,
            "identity": identity, "token": token(room_name, identity), "expiresAt": int(time.time()) + 120}


def authenticated(request):
    return request.headers.get("Authorization") == "Bearer " + settings["deviceAuth"]


async def invitation(request):
    if not authenticated(request):
        return web.Response(status=401)
    server_id = request.match_info["server_id"]
    payload = {"eventId": "event-" + server_id, "serverCallId": server_id,
               "caller": {"handleType": "generic", "value": "fixture-caller", "displayName": "Fixture caller"}, "hasVideo": False}
    if request.query.get("media") == "1":
        payload["media"] = await credentials(server_id)
    return web.json_response(payload)


async def media_credentials(request):
    if not authenticated(request):
        return web.Response(status=401)
    server_id = request.match_info["server_id"]
    fetches[server_id] = fetches.get(server_id, 0) + 1
    body = await request.json()
    if body != {"eventId": "event-" + server_id, "deviceId": "fixture-device"}:
        return web.Response(status=400)
    if server_id.startswith("gone404"):
        return web.Response(status=404)
    if server_id.startswith("gone410"):
        return web.Response(status=410)
    return web.json_response(await credentials(server_id))


async def observations(request):
    if not authenticated(request):
        return web.Response(status=401)
    server_id = request.match_info["server_id"]
    room_name = "native-ci-" + server_id
    client_count = 0
    if room_name in bots:
        client_count = sum(p.identity.startswith("device-") for p in bots[room_name][0].remote_participants.values())
    if server_id in raw_peers:
        client_count = int(raw_peers[server_id][0].connectionState == "connected")
    gate = terminal_gates.get(server_id)
    # Freeze this observation before releasing delayed application processing.
    # The first zero-client observation intentionally cannot prove receipt.
    response = web.json_response({"clients": client_count, "states": list(states.get(room_name, [])),
                                  "fetches": fetches.get(server_id, 0),
                                  "terminalPending": gate is not None and not gate.is_set(),
                                  "terminalTimes": dict(terminal_times.get(server_id, {}))})
    if client_count == 0 and gate is not None and not gate.is_set():
        terminal_times[server_id]["releasedNanos"] = time.monotonic_ns()
        gate.set()
    return response


async def markers(request):
    if not authenticated(request):
        return web.Response(status=401)
    room_name = "native-ci-" + request.match_info["server_id"]
    observation = uplinks.get(room_name)
    if observation is None:
        return web.json_response({"running": False, "frames": 0, "markers": 0})
    if request.method == "POST":
        baseline, error = [], None
        if observation.track is not None:
            try:
                baseline = observation.inbound_reports(await asyncio.wait_for(observation.track.get_stats(), 2))
            except Exception as failure:
                error = type(failure).__name__
        observation.reset()
        observation.stats_error = error
        observation.stats.append({"fromFrame": 0, "frame": 0, "inbound": baseline})
    room = bots[room_name][0]
    publications = [publication for participant in room.remote_participants.values()
                    if participant.identity.startswith("device-")
                    for publication in participant.track_publications.values()
                    if publication.kind == rtc.TrackKind.KIND_AUDIO]
    return web.json_response({"running": observation.running, "frames": observation.frames,
                             "generation": observation.generation,
                             **(await observation.measure()), "error": observation.error,
                             "muted": bool(publications) and all(p.muted for p in publications)})


def output_counter(value):
    """Android RTCStats can encode integral counters as decimal text."""
    if type(value) is int:
        return value
    if isinstance(value, str) and re.fullmatch(r"-?[0-9]+", value):
        try:
            return int(value)
        except ValueError:
            pass
    return None


async def device_output(request):
    """Score actual post-mute output PCM and subscriber counters from the emulator-only tap."""
    if not authenticated(request):
        return web.Response(status=401)
    server_id = request.match_info["server_id"]
    if not server_id.startswith("hooks-"):
        return web.Response(status=400)
    body = await request.json()
    if body.get("reset") is True:
        downlinks[server_id] = MarkerObservation(server_id + ":toDevice")
    observation = downlinks.get(server_id)
    if observation is None or body.get("sampleRate") != 48000 or body.get("firstFrame") != observation.frames:
        return web.Response(status=400)
    data = base64.b64decode(body.get("pcm16", ""), validate=True)
    if len(data) % 2 or len(data) > 48000:
        return web.Response(status=400)
    values = struct.unpack("<" + str(len(data) // 2) + "h", data)
    observation.frames += len(values)
    observation.pcm.extend(values)
    del observation.pcm[:max(0, len(observation.pcm) - 144000)]
    sample = body["stats"]
    if not 0 <= sample["fromFrame"] <= sample["frame"] <= observation.frames or len(observation.stats) >= 128:
        return web.Response(status=400)
    observation.stats.append({"fromFrame": sample["fromFrame"], "frame": sample["frame"],
                              "inbound": [{name: output_counter(sample["counters"].get(name)) for name in (
                                  "inserted_samples_for_deceleration", "removed_samples_for_acceleration", "concealed_samples", "packets_lost")}]})
    return web.json_response({"running": True, "error": None, "frames": observation.frames,
                              "generation": observation.generation, **(await observation.measure())})


async def raw_signaling(request):
    if not authenticated(request):
        return web.Response(status=401)
    server_id = request.match_info["server_id"]
    body = await request.json()
    if body.get("serverCallId") != server_id or body.get("eventId") != "event-" + server_id:
        return web.Response(status=400)
    negotiation = body.get("negotiationId")
    if not isinstance(negotiation, str) or not negotiation:
        return web.Response(status=400)
    if body.get("type") == "offer":
        peer = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        blackhole = MediaBlackhole()

        @peer.on("track")
        def track(track):
            blackhole.addTrack(track)

        await peer.setRemoteDescription(RTCSessionDescription(sdp=body["sdp"], type="offer"))
        peer.addTrack(ToneTrack())
        await blackhole.start()
        await peer.setLocalDescription(await peer.createAnswer())
        if negotiation in closed_negotiations:
            await blackhole.stop()
            await peer.close()
            return web.Response(status=410)
        if server_id in raw_peers:
            previous, consumer, _ = raw_peers.pop(server_id)
            await consumer.stop()
            await previous.close()
        raw_peers[server_id] = peer, blackhole, negotiation
        return web.json_response({"type": "answer", "sdp": peer.localDescription.sdp})
    if body.get("type") == "state":
        state = body.get("state")
        if state == "declined" and server_id.startswith("rtc-decline-"):
            # A deterministic async server receipt, independent of transport disposal.
            gate = asyncio.Event()
            terminal_gates[server_id] = gate
            terminal_times[server_id] = {"receivedNanos": time.monotonic_ns()}
            try:
                await asyncio.wait_for(gate.wait(), timeout=6)
            except asyncio.TimeoutError:
                return web.Response(status=504)
            finally:
                terminal_gates.pop(server_id, None)
            terminal_times[server_id]["recordedNanos"] = time.monotonic_ns()
        if negotiation in closed_negotiations and state in ("prejoined", "answered"):
            return web.Response(status=410)
        states.setdefault("native-ci-" + server_id, []).append(state)
        if state in ("declined", "ended", "filtered", "unanswered"):
            closed_negotiations.add(negotiation)
            if server_id in raw_peers and raw_peers[server_id][2] == negotiation:
                peer, blackhole, _ = raw_peers.pop(server_id)
                await blackhole.stop()
                await peer.close()
        return web.json_response({})
    return web.Response(status=400)


async def proxy(request):
    destination = "http://127.0.0.1:7880" + request.rel_url.path_qs
    headers = {"Authorization": request.headers["Authorization"]} if "Authorization" in request.headers else {}
    async with ClientSession() as session:
        if request.headers.get("Upgrade", "").lower() == "websocket":
            incoming = web.WebSocketResponse()
            await incoming.prepare(request)
            async with session.ws_connect(destination, headers=headers) as outgoing:
                async def pipe(source, target):
                    async for message in source:
                        if message.type == WSMsgType.BINARY:
                            await target.send_bytes(message.data)
                        elif message.type == WSMsgType.TEXT:
                            await target.send_str(message.data)
                        else:
                            break
                tasks = [asyncio.create_task(pipe(incoming, outgoing)), asyncio.create_task(pipe(outgoing, incoming))]
                await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
            return incoming
        async with session.request(request.method, destination, headers=headers) as response:
            return web.Response(status=response.status, body=await response.read())


@web.middleware
async def sanitized_errors(request, handler):
    try:
        return await handler(request)
    except (asyncio.CancelledError, ConnectionResetError):
        raise
    except Exception:
        # Credentials may occur in headers/URLs; never print request or exception details.
        return web.json_response({"error": "fixture-operation-failed"}, status=500)


if __name__ == "__main__":
    if sys.argv[1] == "--analyze":
        analyze_stdin()
        sys.exit(0)
    app = web.Application(middlewares=[sanitized_errors])
    app.on_cleanup.append(analysis_worker.close)
    app.router.add_get("/health", lambda _: web.json_response({"ready": True}))
    app.router.add_get("/fixture/invitation/{server_id}", invitation)
    app.router.add_get("/fixture/observations/{server_id}", observations)
    app.router.add_route("*", "/fixture/markers/{server_id}", markers)
    app.router.add_post("/fixture/output/{server_id}", device_output)
    app.router.add_post("/v1/calls/{server_id}/media-credentials", media_credentials)
    app.router.add_post("/fixture/webrtc/{server_id}", raw_signaling)
    app.router.add_route("*", "/{rest:.*}", proxy)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(directory / "server.crt", directory / "server.key")
    web.run_app(app, host="0.0.0.0", port=9443, ssl_context=tls, access_log=None, print=None)
