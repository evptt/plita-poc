import struct
from dataclasses import replace

import pytest

from app.config import Settings
from app.parsers import (PacketError, build_sensor_packet, parse_relay_packet, parse_sensor_packet,
                         parse_topic)
from app.security import AuthError, GoogleUnavailable, decode_jwt, issue_jwt, verify_google_id_token


# ---------------- топики ----------------

def test_topics():
    assert parse_topic("device/abc/telemetry").kind == "sensor"
    assert parse_topic("relay/r1/power").device_id == "r1"
    assert parse_topic("relay/r1/command") is None
    assert parse_topic("device//telemetry") is None
    assert parse_topic("$SYS/broker/uptime") is None


# ---------------- пакет датчика ----------------

def test_sensor_packet_little_endian_field_order():
    # порядок в пакете: влажность, температура, VOC, радар (раздел 3 ТЗ)
    raw = struct.pack("<fffB", 45.5, 23.25, 312.0, 1)
    assert len(raw) == 13
    r = parse_sensor_packet(raw)
    assert (r.humidity, r.temperature, r.voc, r.radar) == (45.5, 23.25, 312.0, 1)


def test_sensor_packet_big_endian():
    raw = build_sensor_packet(10.0, 20.0, 30.0, 255, byte_order="big")
    r = parse_sensor_packet(raw, byte_order="big")
    assert (r.humidity, r.temperature, r.voc, r.radar) == (10.0, 20.0, 30.0, 255)


@pytest.mark.parametrize("payload", [b"", b"\x00" * 12, b"\x00" * 14])
def test_sensor_packet_wrong_size(payload):
    with pytest.raises(PacketError):
        parse_sensor_packet(payload)


def test_sensor_packet_nan():
    with pytest.raises(PacketError):
        parse_sensor_packet(struct.pack("<fffB", float("nan"), 1, 1, 0))


# ---------------- пакет реле ----------------

def test_relay_json_from_emulator():
    r = parse_relay_packet(b'{"voltage": 218.9, "current": 8.14, "state": 1}')
    assert (r.voltage, r.current, r.state) == (218.9, 8.14, 1)


@pytest.mark.parametrize("payload,state", [
    (b'{"U": 230, "I": 0, "status": "off"}', 0),
    (b'{"voltage": "221.5", "current": "3", "state": true}', 1),
    (b"220.1,4.5,1", 1),
])
def test_relay_variants(payload, state):
    assert parse_relay_packet(payload).state == state


@pytest.mark.parametrize("payload", [b"", b"not json", b'{"voltage": 1}', b'{"voltage":1,"current":1,"state":7}',
                                     b"[1,2,3]", b"\xff\xfe"])
def test_relay_bad(payload):
    with pytest.raises(PacketError):
        parse_relay_packet(payload)


# ---------------- JWT / Google ----------------

@pytest.fixture
def settings(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", "s3cret")
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    return Settings.from_env()


def test_jwt_roundtrip(settings):
    token, ttl = issue_jwt(settings, 42)
    assert ttl == settings.jwt_ttl_hours * 3600
    assert decode_jwt(settings, token) == 42


def test_jwt_wrong_secret(settings):
    token, _ = issue_jwt(settings, 1)
    with pytest.raises(AuthError):
        decode_jwt(replace(settings, jwt_secret="other"), token)


def test_jwt_garbage(settings):
    with pytest.raises(AuthError):
        decode_jwt(settings, "abc.def.ghi")


def test_google_without_client_id(settings):
    with pytest.raises(GoogleUnavailable):
        verify_google_id_token(settings, "x" * 30)


def test_google_garbage_token(settings):
    s = replace(settings, google_client_ids=("web-client.apps.googleusercontent.com",))
    with pytest.raises(AuthError):
        verify_google_id_token(s, "not-a-jwt-token-at-all")


def test_google_audience_check(settings, monkeypatch):
    from google.oauth2 import id_token
    claims = {"aud": "someone-else", "iss": "https://accounts.google.com", "sub": "123",
              "email": "a@b.c", "name": "A"}
    monkeypatch.setattr(id_token, "verify_oauth2_token", lambda *a, **k: claims)
    s = replace(settings, google_client_ids=("web-client",))
    with pytest.raises(AuthError):
        verify_google_id_token(s, "token")
    claims["aud"] = "web-client"
    assert verify_google_id_token(s, "token") == {"google_id": "123", "email": "a@b.c", "name": "A"}


def test_settings_defaults(settings):
    assert settings.seed_devices == (("emu-sensor-1", "sensor"), ("emu-relay-1", "relay"))
    assert settings.sensor_byte_order == "little"
    assert settings.dev_auth_enabled is False
