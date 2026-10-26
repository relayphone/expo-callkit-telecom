#!/usr/bin/env bash
set -euo pipefail
fixture_dir="$RUNNER_TEMP/native-media-fixture"
mkdir -p "$fixture_dir" android/instrumentation/src/main/res/raw android/instrumentation/src/androidTest/assets
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=NativeMediaFixtureCA -keyout "$fixture_dir/ca.key" -out "$fixture_dir/ca.crt" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj /CN=localhost -keyout "$fixture_dir/server.key" -out "$fixture_dir/server.csr" 2>/dev/null
echo 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2' > "$fixture_dir/extensions"
openssl x509 -req -in "$fixture_dir/server.csr" -CA "$fixture_dir/ca.crt" -CAkey "$fixture_dir/ca.key" -CAcreateserial -days 1 -extfile "$fixture_dir/extensions" -out "$fixture_dir/server.crt" 2>/dev/null
cp "$fixture_dir/ca.crt" android/instrumentation/src/main/res/raw/media_ci_ca.pem
python3 - "$fixture_dir" <<'PY'
import json,secrets,sys
from pathlib import Path
directory=Path(sys.argv[1])
values={"key":"ci-key","secret":secrets.token_hex(32),"deviceAuth":secrets.token_hex(24)}
(directory/'settings.json').write_text(json.dumps(values))
(directory/'livekit.yaml').write_text('port: 7880\nrtc:\n  node_ip: 10.0.2.2\n  use_external_ip: false\n  tcp_port: 7881\n  udp_port: 7882\nkeys:\n  ci-key: '+values['secret']+'\nlogging:\n  level: error\n')
Path('android/instrumentation/src/androidTest/assets/media-ci.json').write_text(json.dumps({"baseUrl":"https://10.0.2.2:9443","authentication":values['deviceAuth']}))
PY
chmod 600 "$fixture_dir"/*.key "$fixture_dir/settings.json" "$fixture_dir/livekit.yaml"
sudo ip addr add 10.0.2.2/32 dev lo
docker run --detach --rm --name native-media-fixture --network host --user "$(id -u):$(id -g)" -v "$fixture_dir/livekit.yaml:/etc/livekit.yaml:ro" livekit/livekit-server:v1.13.11 --config /etc/livekit.yaml
timeout 1200s python3 tools/native-media-fixture.py "$fixture_dir" > "$fixture_dir/server.log" 2>&1 &
echo "$!" > "$fixture_dir/pid"
for attempt in {1..40}; do
  if curl --silent --fail http://127.0.0.1:7880 > /dev/null && curl --silent --fail --cacert "$fixture_dir/ca.crt" https://localhost:9443/health > /dev/null; then exit 0; fi
  sleep 1
done
echo 'Native media fixture did not become healthy' >&2
exit 1
