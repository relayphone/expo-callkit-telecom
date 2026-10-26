"""CI-only TLS signaling proxy, credential endpoint and real LiveKit PCM publisher."""
import asyncio
import contextlib
import datetime
import json
import math
from pathlib import Path
import ssl
import struct
import sys
import time

from aiohttp import ClientSession, WSMsgType, web
from livekit import api, rtc

directory = Path(sys.argv[1])
settings = json.loads((directory / "settings.json").read_text())
bots = {}
fetches = {}
states = {}
lock = asyncio.Lock()


def token(room, identity):
    return (api.AccessToken(settings["key"], settings["secret"])
            .with_identity(identity).with_ttl(datetime.timedelta(seconds=120))
            .with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True))
            .to_jwt())


async def publish(room_name):
    room = rtc.Room()

    @room.on("data_received")
    def observed(packet):
        if packet.topic == "voip-sdk.call":
            with contextlib.suppress(Exception):
                states.setdefault(room_name, []).append(json.loads(packet.data)["state"])

    await room.connect("ws://127.0.0.1:7880", token(room_name, "fixture-publisher"))
    source = rtc.AudioSource(48000, 1)
    track = rtc.LocalAudioTrack.create_audio_track("fixture-tone", source)
    await room.local_participant.publish_track(track,
        rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))

    async def frames():
        index = 0
        while True:
            pcm = struct.pack("<480h", *[int(6000 * math.sin(2 * math.pi * 440 * (index + i) / 48000)) for i in range(480)])
            index += 480
            await source.capture_frame(rtc.AudioFrame(pcm, 48000, 1, 480))
            await asyncio.sleep(.01)

    return room, asyncio.create_task(frames())


async def credentials(server_id):
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
               "caller": {"id": "fixture-caller", "displayName": "Fixture caller"}, "hasVideo": False}
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
    return web.json_response({"clients": client_count, "states": states.get(room_name, []),
                              "fetches": fetches.get(server_id, 0)})


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


app = web.Application(middlewares=[sanitized_errors])
app.router.add_get("/health", lambda _: web.json_response({"ready": True}))
app.router.add_get("/fixture/invitation/{server_id}", invitation)
app.router.add_get("/fixture/observations/{server_id}", observations)
app.router.add_post("/v1/calls/{server_id}/media-credentials", media_credentials)
app.router.add_route("*", "/{rest:.*}", proxy)
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls.load_cert_chain(directory / "server.crt", directory / "server.key")
web.run_app(app, host="0.0.0.0", port=9443, ssl_context=tls, access_log=None, print=None)
