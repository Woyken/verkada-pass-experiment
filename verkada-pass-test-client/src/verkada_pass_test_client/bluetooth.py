from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import sys
import time
import tomllib
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import tomli_w
from bleak import BleakClient, BleakScanner
from bless import BlessServer, GATTAttributePermissions, GATTCharacteristicProperties
from nacl.bindings import crypto_kx_client_session_keys, crypto_kx_keypair

from .client import VerkadaPassClient
from .config import AppConfig
from .models import SessionState

logger = logging.getLogger(__name__)

MODE_REMOTE = "remote"
MODE_NEARBY_HTTP = "nearby"
MODE_BLE_CENTRAL = "ble-central"
MODE_BLE_PERIPHERAL = "ble-peripheral"
SUPPORTED_MODES = (MODE_REMOTE, MODE_NEARBY_HTTP, MODE_BLE_CENTRAL, MODE_BLE_PERIPHERAL)

BLE_READER_SERVICE_UUID = "0000FD3B-0000-1000-8000-00805F9B34FB"
BLE_PHONE_SERVICE_UUID = "0000FD3A-0000-1000-8000-00805F9B34FB"
BLE_PHONE_ADVERTISED_SERVICE_UUID = "0000A4DD-0000-1000-8000-00805F9B34FB"
BLE_PUBLIC_KEY_CHARACTERISTIC_UUID = "00001001-0000-1000-8000-00805F9B34FB"
BLE_AUTH_CHARACTERISTIC_UUID = "00002000-0000-1000-8000-00805F9B34FB"
BLE_KEY_TYPE = "BLE_UNLOCK_PUBLIC_KEY_ED25519"
VERKADA_READER_BEACON_UUID = "ac3ef23c-70d8-4773-97ad-b9a566a0fb40"
APPLE_COMPANY_ID = 0x004C
IBEACON_PREFIX = b"\x02\x15"

PERIPHERAL_ERROR_MISSING_USER_ID = 10
PERIPHERAL_ERROR_REQUEST_VALUE_HAS_WRONG_SIZE = 21
PERIPHERAL_ERROR_REQUEST_READER_SERIAL_IS_EMPTY = 22
PERIPHERAL_ERROR_FAILED_TO_RETRIEVE_ENCRYPTION_KEYS = 31
PERIPHERAL_ERROR_NO_AUTH_TAG_AND_NO_ERROR = 50

PERIPHERAL_ERROR_NAMES = {
    PERIPHERAL_ERROR_MISSING_USER_ID: "MISSING_USER_ID",
    PERIPHERAL_ERROR_REQUEST_VALUE_HAS_WRONG_SIZE: "REQUEST_VALUE_HAS_WRONG_SIZE",
    PERIPHERAL_ERROR_REQUEST_READER_SERIAL_IS_EMPTY: "REQUEST_READER_SERIAL_IS_EMPTY",
    PERIPHERAL_ERROR_FAILED_TO_RETRIEVE_ENCRYPTION_KEYS: "FAILED_TO_RETRIEVE_ENCRYPTION_KEYS",
    PERIPHERAL_ERROR_NO_AUTH_TAG_AND_NO_ERROR: "NO_AUTH_TAG_AND_NO_ERROR",
}


def _hex_preview(data: bytes, limit: int = 64) -> str:
    """Return a hex preview of `data`, truncated for readability."""
    if not data:
        return "<empty>"
    if len(data) <= limit:
        return data.hex()
    return f"{data[:limit].hex()}...({len(data)} bytes total)"


def looks_like_verkada_reader_serial(value: str) -> bool:
    if value.startswith("APL"):
        return True
    return len(value) == 14 and value.count("-") == 2 and all(
        character == "-" or character.isdigit() or ("A" <= character <= "Z")
        for character in value
    )


@dataclass(slots=True)
class BleKeyPair:
    public_key: bytes
    private_key: bytes

    @property
    def public_key_base64(self) -> str:
        return base64.b64encode(self.public_key).decode("ascii")

    @property
    def private_key_base64(self) -> str:
        return base64.b64encode(self.private_key).decode("ascii")

    @property
    def public_key_hex(self) -> str:
        return self.public_key.hex()

    @property
    def fingerprint(self) -> str:
        digest = hashlib.sha256(self.public_key_hex.encode("utf-8")).digest()
        return digest.hex()

    def to_toml_dict(self) -> dict[str, str]:
        return {
            "public_key_base64": self.public_key_base64,
            "private_key_base64": self.private_key_base64,
        }

    @classmethod
    def from_toml_dict(cls, data: dict[str, Any]) -> "BleKeyPair":
        public_key_base64 = str(data["public_key_base64"])
        private_key_base64 = str(data["private_key_base64"])
        return cls(
            public_key=base64.b64decode(public_key_base64),
            private_key=base64.b64decode(private_key_base64),
        )


@dataclass(slots=True)
class CentralReader:
    device: Any
    address: str
    name: str
    reader_serial: str
    rssi: int | None = None


@dataclass(slots=True)
class CentralUnlockResult:
    reader_name: str
    reader_address: str
    reader_serial: str
    user_id: str
    payload_size: int
    created_new_key: bool
    registered_key: bool


@dataclass(slots=True)
class BleScanObservation:
    address: str
    name: str | None
    local_name: str | None
    service_uuids: list[str]
    manufacturer_data: dict[int, bytes]
    rssi: int | None = None
    reader_serial: str | None = None
    beacon_uuid: str | None = None
    beacon_major: int | None = None
    beacon_minor: int | None = None

    def markers(self) -> list[str]:
        labels: list[str] = []
        if BLE_READER_SERVICE_UUID.lower() in self.service_uuids:
            labels.append("FD3B")
        if BLE_PHONE_SERVICE_UUID.lower() in self.service_uuids:
            labels.append("FD3A")
        if BLE_PHONE_ADVERTISED_SERVICE_UUID.lower() in self.service_uuids:
            labels.append("A4DD")
        if self.beacon_uuid == VERKADA_READER_BEACON_UUID:
            if self.beacon_major is not None and self.beacon_minor is not None:
                labels.append(f"BEACON={self.beacon_major}/{self.beacon_minor}")
            else:
                labels.append("BEACON")
        if self.reader_serial:
            labels.append(f"serial={self.reader_serial}")
        return labels

    def summary(self) -> str:
        name = self.name or self.local_name or "<unnamed>"
        rssi = f", rssi={self.rssi}" if self.rssi is not None else ""
        labels = self.markers()
        details = f" [{', '.join(labels)}]" if labels else ""
        return f"- {name} @ {self.address}{rssi}{details}"


@dataclass(slots=True)
class BleDiscoveryResult:
    source_label: str
    total_entries: int
    readers: list[CentralReader]
    observations: list[BleScanObservation]
    fd3b_service_count: int
    fd3a_service_count: int
    a4dd_service_count: int
    verkada_beacon_count: int
    serial_candidate_count: int

    def render_summary_lines(self) -> list[str]:
        lines = [
            f"Scan source: {self.source_label}",
            f"Total advertisements seen: {self.total_entries}",
            f"FD3B reader-service advertisers: {self.fd3b_service_count}",
            f"FD3A phone-service advertisers: {self.fd3a_service_count}",
            f"A4DD phone companion advertisers: {self.a4dd_service_count}",
            f"Verkada reader beacon hits ({VERKADA_READER_BEACON_UUID}): {self.verkada_beacon_count}",
            f"Decodable serial candidates: {self.serial_candidate_count}",
            f"Reader candidates usable by this client: {len(self.readers)}",
        ]
        interesting = [item.summary() for item in self.observations[:8]]
        if interesting:
            lines.append("Interesting advertisements:")
            lines.extend(interesting)
        return lines


def validate_mode(value: str) -> str:
    if value not in SUPPORTED_MODES:
        supported = ", ".join(SUPPORTED_MODES)
        raise ValueError(f"Unsupported mode '{value}'. Expected one of: {supported}.")
    return value


def load_or_create_ble_keys(path: Path) -> tuple[BleKeyPair, bool]:
    if path.exists():
        logger.info("Loading existing BLE keys from %s", path)
        with path.open("rb") as handle:
            data = tomllib.load(handle)

        ble_keys = data.get("ble_keys")
        if not isinstance(ble_keys, dict):
            raise ValueError(f"BLE key file at {path} is missing a [ble_keys] table.")
        key_pair = BleKeyPair.from_toml_dict(ble_keys)
        logger.debug(
            "Loaded BLE keys: public_key=%s fingerprint=%s",
            key_pair.public_key_hex,
            key_pair.fingerprint,
        )
        return key_pair, False

    logger.info("No BLE key file at %s. Generating a new X25519 key pair.", path)
    public_key, private_key = crypto_kx_keypair()
    key_pair = BleKeyPair(bytes(public_key), bytes(private_key))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        tomli_w.dump({"ble_keys": key_pair.to_toml_dict()}, handle)
    logger.info(
        "Saved new BLE key pair to %s (public_key=%s, fingerprint=%s)",
        path,
        key_pair.public_key_hex,
        key_pair.fingerprint,
    )
    return key_pair, True


def resolve_ble_user_id(
    *,
    reader_serial: str,
    session: SessionState,
    config: AppConfig,
    override_user_id: str | None = None,
) -> str:
    if override_user_id:
        logger.debug("Resolved user id from CLI override: %s", override_user_id)
        return override_user_id
    if reader_serial in config.reader_user_ids:
        mapped = config.reader_user_ids[reader_serial]
        logger.debug("Resolved user id from reader_user_ids[%s]: %s", reader_serial, mapped)
        return mapped
    if config.ble_user_id:
        logger.debug("Resolved user id from config.ble_user_id: %s", config.ble_user_id)
        return config.ble_user_id
    logger.debug("Resolved user id from session.user_id: %s", session.user_id)
    return session.user_id


def decode_reader_serial_from_scan_record_bytes(raw_scan_record: bytes | None) -> str | None:
    if raw_scan_record is None or len(raw_scan_record) < 23:
        return None
    try:
        decoded = raw_scan_record[9:23].decode("utf-8").strip("\x00").strip()
    except UnicodeDecodeError:
        logger.debug("Raw scan record slice is not valid UTF-8: %s", raw_scan_record.hex())
        return None
    if looks_like_verkada_reader_serial(decoded):
        return decoded
    return None


def extract_raw_scan_record_bytes(platform_data: tuple[Any, ...] | Any) -> list[bytes]:
    if not isinstance(platform_data, tuple) or len(platform_data) < 2:
        return []
    raw_data = platform_data[1]
    result: list[bytes] = []
    for event_args in (getattr(raw_data, "adv", None), getattr(raw_data, "scan", None)):
        advertisement = getattr(event_args, "advertisement", None)
        data_sections = getattr(advertisement, "data_sections", None)
        if data_sections is None:
            continue
        encoded = bytearray()
        for section in data_sections:
            payload = bytes(section.data)
            encoded.append(len(payload) + 1)
            encoded.append(int(section.data_type))
            encoded.extend(payload)
        if encoded:
            result.append(bytes(encoded))
    return result


def decode_reader_serial(
    manufacturer_data: dict[int, bytes],
    raw_scan_records: list[bytes] | None = None,
) -> str | None:
    for raw_scan_record in raw_scan_records or ():
        decoded = decode_reader_serial_from_scan_record_bytes(raw_scan_record)
        if decoded:
            return decoded
    fallback: str | None = None
    for company_id, payload in manufacturer_data.items():
        prefix = bytes((company_id & 0xFF, (company_id >> 8) & 0xFF))
        serial_bytes = prefix + bytes(payload)
        try:
            decoded = serial_bytes.decode("utf-8").strip("\x00").strip()
        except UnicodeDecodeError:
            logger.debug("Manufacturer data is not valid UTF-8: %s", serial_bytes.hex())
            continue
        if not decoded:
            continue
        if looks_like_verkada_reader_serial(decoded):
            return decoded
        if fallback is None:
            fallback = decoded
    return fallback


def decode_verkada_beacon_uuid(manufacturer_data: dict[int, bytes]) -> str | None:
    decoded = decode_verkada_beacon_details(manufacturer_data)
    if decoded is None:
        return None
    return decoded[0]


def derive_beacon_pair_from_reader_serial(reader_serial: str) -> tuple[int, int]:
    digest = hashlib.sha256(reader_serial.encode("utf-8")).hexdigest()
    return int(digest[0:4], 16), int(digest[4:8], 16)


def decode_verkada_beacon_details(manufacturer_data: dict[int, bytes]) -> tuple[str, int, int] | None:
    for company_id, payload in manufacturer_data.items():
        data = bytes(payload)
        if company_id != APPLE_COMPANY_ID or not data.startswith(IBEACON_PREFIX) or len(data) < 18:
            continue
        beacon_uuid = str(uuid.UUID(bytes=data[2:18])).lower()
        if len(data) < 22:
            return beacon_uuid, 0, 0
        major = int.from_bytes(data[18:20], byteorder="big")
        minor = int.from_bytes(data[20:22], byteorder="big")
        return beacon_uuid, major, minor
    return None


def compute_ble_auth_tag(message: bytes, session_tx_key: bytes) -> bytes:
    # Matches libsodium `crypto_auth`, which is HMAC-SHA-512 truncated to 32 bytes.
    # This is NOT the same as HMAC with FIPS 180-4 SHA-512/256 (different initial hash values).
    return hmac.new(session_tx_key, message, digestmod="sha512").digest()[:32]


def build_unlock_payload(
    *,
    ble_keys: BleKeyPair,
    reader_public_key: bytes,
    reader_message: bytes,
    user_id: str,
) -> bytes:
    if len(reader_public_key) != 32:
        raise ValueError(f"Reader public key must be 32 bytes, got {len(reader_public_key)}.")

    logger.debug(
        "Building unlock payload: phone_pk=%s reader_pk=%s reader_message_len=%d user_id=%s",
        ble_keys.public_key_hex,
        reader_public_key.hex(),
        len(reader_message),
        user_id,
    )
    _, tx_key = crypto_kx_client_session_keys(ble_keys.public_key, ble_keys.private_key, reader_public_key)
    logger.debug("Derived TX session key (crypto_kx client): %s", tx_key.hex())
    auth_tag = compute_ble_auth_tag(reader_message, tx_key)
    logger.debug("Computed HMAC-SHA-512[:32] auth tag: %s", auth_tag.hex())
    user_id_bytes = uuid.UUID(user_id).bytes
    payload = ble_keys.public_key + auth_tag + user_id_bytes
    logger.debug(
        "Assembled unlock payload (%d bytes): phone_pk(32) || auth_tag(32) || user_id(16) = %s",
        len(payload),
        payload.hex(),
    )
    return payload


def build_ble_registration_request(config: AppConfig, ble_keys: BleKeyPair) -> dict[str, str]:
    return {
        "publicKey": ble_keys.public_key_hex,
        "platform": config.ble_registration_platform,
        "version": config.ble_registration_version,
        "make": config.ble_registration_make,
        "model": config.ble_registration_model,
        "name": config.ble_registration_name,
        "keyType": BLE_KEY_TYPE,
    }


def ensure_registered_ble_key(
    *,
    api_client: VerkadaPassClient,
    session: SessionState,
    config: AppConfig,
    ble_keys: BleKeyPair,
) -> bool:
    logger.info("Checking whether BLE public key %s is registered with the backend...", ble_keys.fingerprint)
    started = time.perf_counter()
    registered_keys = api_client.get_registered_auth_keys(session)
    logger.debug(
        "Backend returned %d registered key(s) in %.2fs",
        len(registered_keys),
        time.perf_counter() - started,
    )
    for item in registered_keys:
        logger.debug(
            "Registered key candidate: fingerprint=%s keyType=%s",
            item.get("fingerprint"),
            item.get("keyType"),
        )
        if item.get("fingerprint") == ble_keys.fingerprint and item.get("keyType") == BLE_KEY_TYPE:
            logger.info("BLE key is already registered with the backend. Skipping registration.")
            return False

    logger.info("BLE key not yet registered. Submitting POST /{orgId}/keys ...")
    body = build_ble_registration_request(config, ble_keys)
    logger.debug("Registration request body: %s", body)
    started = time.perf_counter()
    api_client.register_public_ble_key(session, body)
    logger.info("Registered new BLE public key with backend in %.2fs", time.perf_counter() - started)
    return True


async def _discover_readers(*, config: AppConfig) -> list[CentralReader]:
    discovery = await discover_ble_readers(config=config)
    return discovery.readers


async def _scan_ble_observations(
    *,
    timeout_seconds: float,
    service_uuids: list[str] | None = None,
) -> list[tuple[Any, BleScanObservation]]:
    logger.info(
        "Starting BLE scan%s (timeout=%.1fs)",
        f" with service filter {service_uuids}" if service_uuids else "",
        timeout_seconds,
    )
    scan_started = time.perf_counter()
    kwargs: dict[str, Any] = {
        "timeout": timeout_seconds,
        "return_adv": True,
    }
    if service_uuids:
        kwargs["service_uuids"] = service_uuids
    discovered = await BleakScanner.discover(**kwargs)
    scan_elapsed = time.perf_counter() - scan_started
    logger.info("BLE scan complete in %.2fs", scan_elapsed)

    if isinstance(discovered, dict):
        items = list(discovered.values())
    else:
        items = list(discovered)
    logger.debug("Scanner returned %d advertising entries", len(items))

    observations: list[tuple[Any, BleScanObservation]] = []
    for item in items:
        if isinstance(item, tuple):
            device, advertisement = item
        else:
            device, advertisement = item, None

        manufacturer_data_raw = getattr(advertisement, "manufacturer_data", {}) or {}
        manufacturer_data = {int(key): bytes(value) for key, value in manufacturer_data_raw.items()}
        normalized_service_uuids = [str(value).lower() for value in (getattr(advertisement, "service_uuids", []) or [])]
        rssi = getattr(advertisement, "rssi", None)
        raw_scan_records = extract_raw_scan_record_bytes(getattr(advertisement, "platform_data", ()))
        device_address = str(getattr(device, "address", "<unknown>"))
        device_name = getattr(device, "name", None)
        local_name = getattr(advertisement, "local_name", None)

        beacon_details = decode_verkada_beacon_details(manufacturer_data)
        observation = BleScanObservation(
            address=device_address,
            name=str(device_name) if device_name else None,
            local_name=str(local_name) if local_name else None,
            service_uuids=normalized_service_uuids,
            manufacturer_data=manufacturer_data,
            rssi=int(rssi) if isinstance(rssi, int) else None,
            reader_serial=decode_reader_serial(
                manufacturer_data,
                raw_scan_records=raw_scan_records,
            ),
            beacon_uuid=beacon_details[0] if beacon_details else None,
            beacon_major=beacon_details[1] if beacon_details else None,
            beacon_minor=beacon_details[2] if beacon_details else None,
        )
        logger.debug(
            "Scanned device: address=%s name=%s local_name=%s rssi=%s services=%s manufacturer_data=%s markers=%s",
            device_address,
            observation.name,
            observation.local_name,
            observation.rssi,
            observation.service_uuids,
            {hex(key): value.hex() for key, value in manufacturer_data.items()},
            observation.markers(),
        )
        observations.append((device, observation))

    return observations


def _build_ble_discovery_result(
    observations: list[tuple[Any, BleScanObservation]],
    *,
    source_label: str,
) -> BleDiscoveryResult:
    readers: list[CentralReader] = []
    fd3b_service_count = 0
    fd3a_service_count = 0
    a4dd_service_count = 0
    verkada_beacon_count = 0
    serial_candidate_count = 0
    interesting: list[BleScanObservation] = []

    for device, observation in observations:
        has_fd3b = BLE_READER_SERVICE_UUID.lower() in observation.service_uuids
        has_fd3a = BLE_PHONE_SERVICE_UUID.lower() in observation.service_uuids
        has_a4dd = BLE_PHONE_ADVERTISED_SERVICE_UUID.lower() in observation.service_uuids
        has_beacon = observation.beacon_uuid == VERKADA_READER_BEACON_UUID

        if has_fd3b:
            fd3b_service_count += 1
        if has_fd3a:
            fd3a_service_count += 1
        if has_a4dd:
            a4dd_service_count += 1
        if has_beacon:
            verkada_beacon_count += 1
        if observation.reader_serial:
            serial_candidate_count += 1

        if has_fd3b or has_fd3a or has_a4dd or has_beacon or observation.reader_serial:
            interesting.append(observation)

        if not observation.reader_serial:
            continue

        if not looks_like_verkada_reader_serial(observation.reader_serial) and not has_fd3b:
            logger.debug(
                "Ignoring decodable serial candidate %r at %s because it did not match the known Verkada formats and the device did not advertise FD3B.",
                observation.reader_serial,
                observation.address,
            )
            continue

        if not observation.reader_serial.startswith("APL"):
            logger.info(
                "Candidate %s has a non-APL Verkada-style serial %s but did advertise FD3B. Including anyway.",
                observation.address,
                observation.reader_serial,
            )

        reader_name = observation.name or observation.local_name or observation.address
        readers.append(
            CentralReader(
                device=device,
                address=observation.address,
                name=reader_name,
                reader_serial=observation.reader_serial,
                rssi=observation.rssi,
            )
        )

    return BleDiscoveryResult(
        source_label=source_label,
        total_entries=len(observations),
        readers=readers,
        observations=interesting,
        fd3b_service_count=fd3b_service_count,
        fd3a_service_count=fd3a_service_count,
        a4dd_service_count=a4dd_service_count,
        verkada_beacon_count=verkada_beacon_count,
        serial_candidate_count=serial_candidate_count,
    )


async def discover_ble_readers(*, config: AppConfig) -> BleDiscoveryResult:
    filtered_observations = await _scan_ble_observations(
        timeout_seconds=config.ble_scan_timeout_seconds,
        service_uuids=[BLE_READER_SERVICE_UUID],
    )
    filtered_result = _build_ble_discovery_result(filtered_observations, source_label="FD3B-filtered scan")
    if filtered_result.readers:
        logger.info(
            "FD3B-filtered scan found %d reader candidate(s).",
            len(filtered_result.readers),
        )
        return filtered_result

    logger.warning(
        "FD3B-filtered scan found no reader candidates. Running unfiltered BLE diagnostics."
    )
    unfiltered_observations = await _scan_ble_observations(
        timeout_seconds=config.ble_scan_timeout_seconds,
        service_uuids=None,
    )
    result = _build_ble_discovery_result(unfiltered_observations, source_label="unfiltered scan")
    logger.info(
        "Unfiltered scan summary: fd3b=%d fd3a=%d a4dd=%d beacon=%d serial=%d readers=%d",
        result.fd3b_service_count,
        result.fd3a_service_count,
        result.a4dd_service_count,
        result.verkada_beacon_count,
        result.serial_candidate_count,
        len(result.readers),
    )
    return result


async def run_ble_central_mode(
    *,
    api_client: VerkadaPassClient,
    session: SessionState,
    config: AppConfig,
    ble_keys_path: Path,
    choose_reader: Callable[[list[CentralReader]], CentralReader],
    override_user_id: str | None = None,
    discovery: BleDiscoveryResult | None = None,
) -> CentralUnlockResult:
    logger.info("=== BLE CENTRAL MODE START ===")
    logger.info("Step 1/7: Load or create local BLE key pair")
    ble_keys, created_new_key = load_or_create_ble_keys(ble_keys_path)

    logger.info("Step 2/7: Ensure BLE public key is registered with backend")
    registered_key = ensure_registered_ble_key(
        api_client=api_client,
        session=session,
        config=config,
        ble_keys=ble_keys,
    )

    logger.info("Step 3/7: Scan for nearby Verkada readers")
    if discovery is None:
        discovery = await discover_ble_readers(config=config)
    readers = discovery.readers
    if not readers:
        logger.error("No nearby BLE readers exposing service %s were found.", BLE_READER_SERVICE_UUID)
        raise RuntimeError(
            "No nearby BLE readers exposing the Verkada reader service were found.\n"
            + "\n".join(discovery.render_summary_lines())
        )

    logger.info("Step 4/7: Choose a reader from %d candidate(s)", len(readers))
    selected_reader = choose_reader(readers)
    logger.info(
        "Selected reader: name=%s address=%s serial=%s rssi=%s",
        selected_reader.name,
        selected_reader.address,
        selected_reader.reader_serial,
        selected_reader.rssi,
    )

    user_id = resolve_ble_user_id(
        reader_serial=selected_reader.reader_serial,
        session=session,
        config=config,
        override_user_id=override_user_id,
    )
    logger.info("Resolved user id for unlock payload: %s", user_id)

    logger.info(
        "Step 5/7: Connect to reader %s (service filter=%s)",
        selected_reader.address,
        BLE_READER_SERVICE_UUID,
    )
    connect_started = time.perf_counter()
    async with BleakClient(selected_reader.device, services=[BLE_READER_SERVICE_UUID]) as ble_client:
        logger.info(
            "Connected to %s in %.2fs (mtu_size=%s)",
            selected_reader.address,
            time.perf_counter() - connect_started,
            getattr(ble_client, "mtu_size", "unknown"),
        )

        logger.info(
            "Step 6/7: Read reader public key from characteristic %s",
            BLE_PUBLIC_KEY_CHARACTERISTIC_UUID,
        )
        read_started = time.perf_counter()
        reader_public_key = bytes(
            await ble_client.read_gatt_char(BLE_PUBLIC_KEY_CHARACTERISTIC_UUID, use_cached=False)
        )
        logger.info(
            "Read reader public key in %.2fs: %d bytes, value=%s",
            time.perf_counter() - read_started,
            len(reader_public_key),
            reader_public_key.hex(),
        )
        if len(reader_public_key) != 32:
            logger.error(
                "Reader public key has unexpected length %d (expected 32). Aborting before write.",
                len(reader_public_key),
            )

        payload = build_unlock_payload(
            ble_keys=ble_keys,
            reader_public_key=reader_public_key,
            reader_message=selected_reader.reader_serial.encode("utf-8"),
            user_id=user_id,
        )

        logger.info(
            "Step 7/7: Write %d-byte unlock payload to characteristic %s (response=False)",
            len(payload),
            BLE_AUTH_CHARACTERISTIC_UUID,
        )
        logger.debug("Payload bytes: %s", payload.hex())
        write_started = time.perf_counter()
        await ble_client.write_gatt_char(BLE_AUTH_CHARACTERISTIC_UUID, payload, response=False)
        logger.info(
            "Write completed in %.2fs. Disconnecting...",
            time.perf_counter() - write_started,
        )

    logger.info("=== BLE CENTRAL MODE COMPLETE ===")
    return CentralUnlockResult(
        reader_name=selected_reader.name,
        reader_address=selected_reader.address,
        reader_serial=selected_reader.reader_serial,
        user_id=user_id,
        payload_size=len(payload),
        created_new_key=created_new_key,
        registered_key=registered_key,
    )


class PeripheralBleServer:
    def __init__(
        self,
        *,
        api_client: VerkadaPassClient,
        session: SessionState,
        config: AppConfig,
        ble_keys_path: Path,
        override_user_id: str | None = None,
    ) -> None:
        self._api_client = api_client
        self._session = session
        self._config = config
        self._ble_keys_path = ble_keys_path
        self._override_user_id = override_user_id
        self._server: BlessServer | None = None
        self._response_value = bytearray([PERIPHERAL_ERROR_NO_AUTH_TAG_AND_NO_ERROR])
        self._request_count = 0

    def _set_error_response(self, code: int, characteristic: Any, request_value: bytes) -> None:
        name = PERIPHERAL_ERROR_NAMES.get(code, f"UNKNOWN({code})")
        logger.warning(
            "Peripheral request #%d -> error %s (%d). Request preview=%s",
            self._request_count,
            name,
            code,
            _hex_preview(request_value),
        )
        self._response_value = bytearray([code])
        characteristic.value = bytearray(request_value)

    def _read_request(self, characteristic, **_: Any) -> bytearray:
        char_uuid = getattr(characteristic, "uuid", "<unknown>")
        if len(self._response_value) == 1:
            code = self._response_value[0]
            name = PERIPHERAL_ERROR_NAMES.get(code, f"UNKNOWN({code})")
            logger.info(
                "Peripheral READ on %s: returning error byte %s (%d)",
                char_uuid,
                name,
                code,
            )
        else:
            logger.info(
                "Peripheral READ on %s: returning %d-byte response payload",
                char_uuid,
                len(self._response_value),
            )
            logger.debug("Read response bytes: %s", bytes(self._response_value).hex())
        characteristic.value = self._response_value
        return self._response_value

    def _write_request(self, characteristic, value: Any, **_: Any) -> None:
        self._request_count += 1
        char_uuid = getattr(characteristic, "uuid", "<unknown>")
        request_value = bytes(value)
        logger.info(
            "Peripheral WRITE #%d on %s: %d bytes received",
            self._request_count,
            char_uuid,
            len(request_value),
        )
        logger.debug("Request bytes: %s", _hex_preview(request_value, limit=128))

        if len(request_value) < 32:
            self._set_error_response(
                PERIPHERAL_ERROR_REQUEST_VALUE_HAS_WRONG_SIZE,
                characteristic,
                request_value,
            )
            return

        reader_public_key = request_value[:32]
        reader_serial_bytes = request_value[32:]
        logger.debug("Reader public key (32 bytes): %s", reader_public_key.hex())
        logger.debug("Reader serial bytes (%d): %s", len(reader_serial_bytes), reader_serial_bytes.hex())

        try:
            reader_serial = reader_serial_bytes.decode("utf-8").strip()
        except UnicodeDecodeError:
            logger.warning("Reader serial bytes are not valid UTF-8")
            self._set_error_response(
                PERIPHERAL_ERROR_REQUEST_READER_SERIAL_IS_EMPTY,
                characteristic,
                request_value,
            )
            return

        if not reader_serial:
            self._set_error_response(
                PERIPHERAL_ERROR_REQUEST_READER_SERIAL_IS_EMPTY,
                characteristic,
                request_value,
            )
            return

        logger.info("Decoded reader serial: %r", reader_serial)

        ble_keys, _ = load_or_create_ble_keys(self._ble_keys_path)
        user_id = resolve_ble_user_id(
            reader_serial=reader_serial,
            session=self._session,
            config=self._config,
            override_user_id=self._override_user_id,
        )
        logger.info("Resolved user id for reader %s: %s", reader_serial, user_id)

        try:
            payload = build_unlock_payload(
                ble_keys=ble_keys,
                reader_public_key=reader_public_key,
                reader_message=reader_serial_bytes,
                user_id=user_id,
            )
        except ValueError as exc:
            logger.warning("Failed to build unlock payload (ValueError): %s", exc)
            self._set_error_response(
                PERIPHERAL_ERROR_MISSING_USER_ID,
                characteristic,
                request_value,
            )
            return
        except TypeError as exc:
            logger.warning("Failed to build unlock payload (TypeError): %s", exc)
            self._set_error_response(
                PERIPHERAL_ERROR_FAILED_TO_RETRIEVE_ENCRYPTION_KEYS,
                characteristic,
                request_value,
            )
            return

        logger.info(
            "Peripheral request #%d processed. Response payload (%d bytes) staged for next READ.",
            self._request_count,
            len(payload),
        )
        self._response_value = bytearray(payload)
        characteristic.value = bytearray(request_value)

    async def run(self) -> None:
        logger.info("=== BLE PERIPHERAL MODE START ===")
        logger.info("Step 1/4: Load or create local BLE key pair")
        ble_keys, _ = load_or_create_ble_keys(self._ble_keys_path)

        logger.info("Step 2/4: Ensure BLE public key is registered with backend")
        ensure_registered_ble_key(
            api_client=self._api_client,
            session=self._session,
            config=self._config,
            ble_keys=ble_keys,
        )

        logger.info("Step 3/4: Create GATT server and characteristics")
        loop = asyncio.get_running_loop()
        server_name = self._config.ble_registration_name
        logger.info("Creating BlessServer with name=%r", server_name)
        server = BlessServer(name=server_name, loop=loop)
        server.read_request_func = self._read_request
        server.write_request_func = self._write_request

        logger.info("Adding GATT service %s", BLE_PHONE_SERVICE_UUID)
        await server.add_new_service(BLE_PHONE_SERVICE_UUID)

        logger.info(
            "Adding characteristic %s (read) under service %s",
            BLE_AUTH_CHARACTERISTIC_UUID,
            BLE_PHONE_SERVICE_UUID,
        )
        await server.add_new_characteristic(
            BLE_PHONE_SERVICE_UUID,
            BLE_AUTH_CHARACTERISTIC_UUID,
            GATTCharacteristicProperties.read,
            bytearray(self._response_value),
            GATTAttributePermissions.readable,
        )

        logger.info(
            "Adding characteristic %s (write_without_response) under service %s",
            BLE_PUBLIC_KEY_CHARACTERISTIC_UUID,
            BLE_PHONE_SERVICE_UUID,
        )
        await server.add_new_characteristic(
            BLE_PHONE_SERVICE_UUID,
            BLE_PUBLIC_KEY_CHARACTERISTIC_UUID,
            GATTCharacteristicProperties.write_without_response,
            None,
            GATTAttributePermissions.writeable,
        )

        advertise_service_uuids = [BLE_PHONE_SERVICE_UUID]
        if sys.platform == "win32":
            logger.warning(
                "Skipping extra advertised service %s on Windows. Bless/WinRT advertises services "
                "separately, while the APK advertises FD3A+A4DD together; advertising only FD3A "
                "gives the live reader test the best chance of seeing a single connectable GATT service.",
                BLE_PHONE_ADVERTISED_SERVICE_UUID,
            )
        else:
            logger.info(
                "Adding advertised service %s (empty GATT service so bless will advertise this UUID)",
                BLE_PHONE_ADVERTISED_SERVICE_UUID,
            )
            await server.add_new_service(BLE_PHONE_ADVERTISED_SERVICE_UUID)
            advertise_service_uuids.append(BLE_PHONE_ADVERTISED_SERVICE_UUID)

        self._server = server
        logger.info(
            "Step 4/4: Start advertising (services=%s)",
            advertise_service_uuids,
        )
        start_started = time.perf_counter()
        await server.start()
        logger.info(
            "GATT server started in %.2fs. Waiting for reader connections. Press Ctrl+C to stop.",
            time.perf_counter() - start_started,
        )
        try:
            while True:
                await asyncio.sleep(3600)
        except asyncio.CancelledError:
            logger.info("Peripheral mode cancelled; shutting down server.")
            raise
        finally:
            logger.info("Stopping GATT server")
            try:
                await server.stop()
                logger.info("GATT server stopped cleanly")
            except Exception:
                logger.exception("Error while stopping GATT server")
            logger.info("=== BLE PERIPHERAL MODE COMPLETE ===")


async def run_ble_peripheral_mode(
    *,
    api_client: VerkadaPassClient,
    session: SessionState,
    config: AppConfig,
    ble_keys_path: Path,
    override_user_id: str | None = None,
) -> None:
    server = PeripheralBleServer(
        api_client=api_client,
        session=session,
        config=config,
        ble_keys_path=ble_keys_path,
        override_user_id=override_user_id,
    )
    await server.run()
