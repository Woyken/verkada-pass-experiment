from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import tomllib
import tomli_w

from .models import SessionState


@dataclass(slots=True)
class AppConfig:
    email: str | None = None
    org_short_name: str | None = None
    magic_link_url: str | None = None
    mode: str | None = None
    unlock_method: str = "mobile"
    request_magic_link_first: bool = True
    session_file: str = ".verkada-pass-session.toml"
    ble_keys_file: str = ".verkada-pass-ble.toml"
    ble_user_id: str | None = None
    ble_scan_timeout_seconds: float = 10.0
    ble_registration_platform: str = "ANDROID"
    ble_registration_version: str = "python-test-client"
    ble_registration_make: str = "python-test-client"
    ble_registration_model: str = "python-test-client"
    ble_registration_name: str = "python-test-client"
    reader_user_ids: dict[str, str] = field(default_factory=dict)
    timeout_seconds: float = 30.0

    @classmethod
    def from_mapping(cls, data: dict[str, Any]) -> "AppConfig":
        return cls(
            email=_string_value(data.get("email")),
            org_short_name=_string_value(data.get("org_short_name")),
            magic_link_url=_string_value(data.get("magic_link_url")),
            mode=_string_value(data.get("mode")),
            unlock_method=_string_value(data.get("unlock_method")) or "mobile",
            request_magic_link_first=bool(data.get("request_magic_link_first", True)),
            session_file=_string_value(data.get("session_file")) or ".verkada-pass-session.toml",
            ble_keys_file=_string_value(data.get("ble_keys_file")) or ".verkada-pass-ble.toml",
            ble_user_id=_string_value(data.get("ble_user_id")),
            ble_scan_timeout_seconds=float(data.get("ble_scan_timeout_seconds", 10.0)),
            ble_registration_platform=_string_value(data.get("ble_registration_platform")) or "ANDROID",
            ble_registration_version=_string_value(data.get("ble_registration_version")) or "python-test-client",
            ble_registration_make=_string_value(data.get("ble_registration_make")) or "python-test-client",
            ble_registration_model=_string_value(data.get("ble_registration_model")) or "python-test-client",
            ble_registration_name=_string_value(data.get("ble_registration_name")) or "python-test-client",
            reader_user_ids=_string_mapping(data.get("reader_user_ids")),
            timeout_seconds=float(data.get("timeout_seconds", 30.0)),
        )


def _string_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _string_mapping(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}

    result: dict[str, str] = {}
    for raw_key, raw_value in value.items():
        key = _string_value(raw_key)
        mapped_value = _string_value(raw_value)
        if key and mapped_value:
            result[key] = mapped_value
    return result


def load_config(path: Path) -> AppConfig:
    if not path.exists():
        return AppConfig()

    with path.open("rb") as handle:
        data = tomllib.load(handle)

    return AppConfig.from_mapping(data)


def resolve_session_path(config_path: Path, config: AppConfig) -> Path:
    return _resolve_path(config_path, config.session_file)


def resolve_ble_keys_path(config_path: Path, config: AppConfig) -> Path:
    return _resolve_path(config_path, config.ble_keys_file)


def _resolve_path(config_path: Path, configured_path: str) -> Path:
    target_path = Path(configured_path)
    if target_path.is_absolute():
        return target_path
    return config_path.parent / target_path


def load_session(path: Path) -> SessionState | None:
    if not path.exists():
        return None

    with path.open("rb") as handle:
        data = tomllib.load(handle)

    session = data.get("session")
    if not isinstance(session, dict):
        raise ValueError(f"Session file at {path} is missing a [session] table.")

    return SessionState.from_toml_dict(session)


def save_session(path: Path, session: SessionState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        tomli_w.dump({"session": session.to_toml_dict()}, handle)


def delete_session(path: Path) -> None:
    if path.exists():
        path.unlink()
