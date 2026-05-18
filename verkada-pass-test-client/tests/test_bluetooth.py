from __future__ import annotations

import hashlib
import hmac
import unittest
import uuid

from nacl.bindings import crypto_kx_client_session_keys, crypto_kx_seed_keypair

from verkada_pass_test_client.bluetooth import (
    BleKeyPair,
    build_ble_registration_request,
    build_unlock_payload,
    decode_reader_serial,
    resolve_ble_user_id,
)
from verkada_pass_test_client.config import AppConfig
from verkada_pass_test_client.models import SessionState


class BluetoothHelpersTest(unittest.TestCase):
    def test_decode_reader_serial_from_manufacturer_data(self) -> None:
        manufacturer_data = {0x5041: b"L123456"}
        self.assertEqual(decode_reader_serial(manufacturer_data), "APL123456")

    def test_build_unlock_payload_matches_expected_layout(self) -> None:
        local_public_key, local_private_key = crypto_kx_seed_keypair(bytes(range(32)))
        reader_public_key, _ = crypto_kx_seed_keypair(bytes(range(32, 64)))
        ble_keys = BleKeyPair(bytes(local_public_key), bytes(local_private_key))
        user_id = "12345678-1234-5678-1234-567812345678"
        reader_serial = b"APL123456"

        payload = build_unlock_payload(
            ble_keys=ble_keys,
            reader_public_key=bytes(reader_public_key),
            reader_message=reader_serial,
            user_id=user_id,
        )

        _, tx_key = crypto_kx_client_session_keys(
            bytes(local_public_key),
            bytes(local_private_key),
            bytes(reader_public_key),
        )
        expected = bytes(local_public_key) + hmac.new(tx_key, reader_serial, digestmod="sha512").digest()[:32]
        expected += uuid.UUID(user_id).bytes
        self.assertEqual(payload, expected)

    def test_resolve_ble_user_id_prefers_override_then_mapping_then_config_then_session(self) -> None:
        session = SessionState(
            organization_id="org",
            user_id="session-user-id",
            user_token="token",
            email="user@example.com",
        )

        config = AppConfig(
            ble_user_id="config-user-id",
            reader_user_ids={"APL123": "mapped-user-id"},
        )

        self.assertEqual(
            resolve_ble_user_id(
                reader_serial="APL123",
                session=session,
                config=config,
                override_user_id="override-user-id",
            ),
            "override-user-id",
        )
        self.assertEqual(
            resolve_ble_user_id(
                reader_serial="APL123",
                session=session,
                config=config,
            ),
            "mapped-user-id",
        )
        self.assertEqual(
            resolve_ble_user_id(
                reader_serial="UNKNOWN",
                session=session,
                config=config,
            ),
            "config-user-id",
        )
        self.assertEqual(
            resolve_ble_user_id(
                reader_serial="UNKNOWN",
                session=session,
                config=AppConfig(),
            ),
            "session-user-id",
        )

    def test_ble_registration_request_uses_apk_style_hex_encoding(self) -> None:
        local_public_key, local_private_key = crypto_kx_seed_keypair(bytes(range(32)))
        ble_keys = BleKeyPair(bytes(local_public_key), bytes(local_private_key))

        config = AppConfig(
            ble_registration_platform="ANDROID",
            ble_registration_version="14",
            ble_registration_make="Google",
            ble_registration_model="Pixel",
            ble_registration_name="Pixel",
        )

        request = build_ble_registration_request(config, ble_keys)

        self.assertEqual(request["publicKey"], bytes(local_public_key).hex())
        expected_fingerprint = hashlib.sha256(bytes(local_public_key).hex().encode("utf-8")).hexdigest()
        self.assertEqual(ble_keys.fingerprint, expected_fingerprint)
        self.assertEqual(request["platform"], "ANDROID")
        self.assertEqual(request["version"], "14")
        self.assertEqual(request["make"], "Google")
        self.assertEqual(request["model"], "Pixel")
        self.assertEqual(request["name"], "Pixel")
