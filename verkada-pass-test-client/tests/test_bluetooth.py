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
    decode_reader_serial_from_scan_record_bytes,
    derive_beacon_pair_from_reader_serial,
    decode_verkada_beacon_details,
    decode_verkada_beacon_uuid,
    extract_raw_scan_record_bytes,
    looks_like_verkada_reader_serial,
    resolve_ble_user_id,
)
from verkada_pass_test_client.config import AppConfig
from verkada_pass_test_client.models import SessionState


class BluetoothHelpersTest(unittest.TestCase):
    def test_looks_like_verkada_reader_serial_accepts_known_formats(self) -> None:
        self.assertTrue(looks_like_verkada_reader_serial("APL123456"))
        self.assertTrue(looks_like_verkada_reader_serial("DMLD-HT99-NT7H"))
        self.assertFalse(looks_like_verkada_reader_serial("u\x00371M\x04\x02\x0c\x08"))

    def test_decode_reader_serial_from_manufacturer_data(self) -> None:
        manufacturer_data = {0x5041: b"L123456"}
        self.assertEqual(decode_reader_serial(manufacturer_data), "APL123456")

    def test_decode_reader_serial_from_raw_scan_record_bytes(self) -> None:
        raw_scan_record = bytes.fromhex("02010603033bfd0f09444d4c442d485439392d4e543748")
        self.assertEqual(
            decode_reader_serial_from_scan_record_bytes(raw_scan_record),
            "DMLD-HT99-NT7H",
        )
        self.assertEqual(
            decode_reader_serial({}, raw_scan_records=[raw_scan_record]),
            "DMLD-HT99-NT7H",
        )

    def test_extract_raw_scan_record_bytes_from_winrt_platform_data(self) -> None:
        class FakeSection:
            def __init__(self, data_type: int, payload: bytes) -> None:
                self.data_type = data_type
                self.data = payload

        class FakeAdvertisement:
            def __init__(self, sections: list[FakeSection]) -> None:
                self.data_sections = sections

        class FakeEventArgs:
            def __init__(self, sections: list[FakeSection]) -> None:
                self.advertisement = FakeAdvertisement(sections)

        class FakeRawAdvData:
            def __init__(self, adv: object | None, scan: object | None) -> None:
                self.adv = adv
                self.scan = scan

        adv_sections = [
            FakeSection(0x01, b"\x06"),
            FakeSection(0x03, bytes.fromhex("3bfd")),
            FakeSection(0x09, b"DMLD-HT99-NT7H"),
        ]
        scan_sections = [FakeSection(0x0A, b"\xec")]
        raw_scan_records = extract_raw_scan_record_bytes((object(), FakeRawAdvData(FakeEventArgs(adv_sections), FakeEventArgs(scan_sections))))
        self.assertEqual(
            raw_scan_records,
            [
                bytes.fromhex("02010603033bfd0f09444d4c442d485439392d4e543748"),
                bytes.fromhex("020aec"),
            ],
        )

    def test_decode_verkada_beacon_uuid_from_ibeacon_payload(self) -> None:
        manufacturer_data = {
            0x004C: bytes.fromhex("0215ac3ef23c70d8477397adb9a566a0fb4000010002c5"),
        }
        self.assertEqual(
            decode_verkada_beacon_uuid(manufacturer_data),
            "ac3ef23c-70d8-4773-97ad-b9a566a0fb40",
        )
        self.assertEqual(
            decode_verkada_beacon_details(manufacturer_data),
            ("ac3ef23c-70d8-4773-97ad-b9a566a0fb40", 1, 2),
        )

    def test_reader_serial_hash_matches_beacon_pair(self) -> None:
        self.assertEqual(
            derive_beacon_pair_from_reader_serial("DMLD-HT99-NT7H"),
            (17620, 26582),
        )
        self.assertEqual(
            derive_beacon_pair_from_reader_serial("DMLX-PHHJ-EX9L"),
            (49886, 41906),
        )

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
