from __future__ import annotations

import asyncio
import argparse
import logging
from pathlib import Path
import sys
from typing import Sequence

from .bluetooth import (
    MODE_BLE_CENTRAL,
    MODE_BLE_PERIPHERAL,
    MODE_REMOTE,
    SUPPORTED_MODES,
    CentralReader,
    run_ble_central_mode,
    run_ble_peripheral_mode,
    validate_mode,
)
from .client import VerkadaPassClient
from .config import (
    AppConfig,
    delete_session,
    load_config,
    load_session,
    resolve_ble_keys_path,
    resolve_session_path,
    save_session,
)
from .models import DoorRecord, MagicLinkContext, SessionState


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="verkada-pass-test-client",
        description="Interactive Verkada Pass test client for magic-link login, remote unlock, and BLE central/peripheral flows.",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=project_root() / "config.toml",
        help="Path to the TOML config file. Defaults to ./config.toml.",
    )
    parser.add_argument(
        "--force-login",
        action="store_true",
        help="Ignore any saved session and go through the magic-link flow again.",
    )
    parser.add_argument(
        "--magic-link-url",
        help="Provide the full magic-link URL directly instead of reading it from config.toml or prompting.",
    )
    parser.add_argument(
        "--request-link-only",
        action="store_true",
        help="Send the magic-link email request and exit without redeeming it.",
    )
    parser.add_argument(
        "--list-only",
        action="store_true",
        help="List doors after login and exit without unlocking one. Remote mode only.",
    )
    parser.add_argument(
        "--logout",
        action="store_true",
        help="Delete the saved session file and exit.",
    )
    parser.add_argument(
        "--unlock-method",
        help='Override the remote unlock method. Defaults to the config value or "mobile".',
    )
    parser.add_argument(
        "--mode",
        choices=SUPPORTED_MODES,
        help="Choose the operation mode: remote, ble-central, or ble-peripheral.",
    )
    parser.add_argument(
        "--ble-user-id",
        help="Override the BLE user ID used for central/peripheral unlock payloads.",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="count",
        default=0,
        help="Increase log verbosity. -v enables INFO across the app, -vv enables DEBUG (including bleak/bless).",
    )
    parser.add_argument(
        "--log-file",
        type=Path,
        help="Also write logs to the given file (always at DEBUG level regardless of console verbosity).",
    )
    return parser


def setup_logging(verbose: int, log_file: Path | None) -> None:
    """Configure root logging based on -v/-vv and optional --log-file."""
    if verbose >= 2:
        console_level = logging.DEBUG
    elif verbose >= 1:
        console_level = logging.INFO
    else:
        console_level = logging.WARNING

    fmt = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
    formatter = logging.Formatter(fmt)

    root = logging.getLogger()
    # Reset handlers so repeated invocations (e.g. tests) don't stack output.
    for handler in list(root.handlers):
        root.removeHandler(handler)
    root.setLevel(logging.DEBUG if log_file else console_level)

    console_handler = logging.StreamHandler(sys.stderr)
    console_handler.setLevel(console_level)
    console_handler.setFormatter(formatter)
    root.addHandler(console_handler)

    if log_file is not None:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(log_file, encoding="utf-8")
        file_handler.setLevel(logging.DEBUG)
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)

    # Always surface our own step-numbered INFO logs even without -v.
    logging.getLogger("verkada_pass_test_client").setLevel(
        logging.DEBUG if verbose >= 2 or log_file else logging.INFO
    )

    # Library logs only when explicitly verbose.
    library_level = logging.DEBUG if verbose >= 2 else logging.WARNING
    for name in ("bleak", "bless", "httpx", "httpcore"):
        logging.getLogger(name).setLevel(library_level)


def project_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    setup_logging(args.verbose, args.log_file)

    config_path = args.config.resolve()
    try:
        config = load_config(config_path)
        session_path = resolve_session_path(config_path, config)

        if args.logout:
            delete_session(session_path)
            print(f"Removed saved session: {session_path}")
            return 0

        with VerkadaPassClient(timeout_seconds=config.timeout_seconds) as client:
            if args.request_link_only:
                email, org_short_name = prompt_login_identity(config)
                client.request_magic_link(email, org_short_name)
                print("Magic link requested. Copy the URL from your email and then run the app again.")
                return 0

            session = ensure_session(
                client=client,
                config=config,
                session_path=session_path,
                force_login=args.force_login,
                magic_link_override=args.magic_link_url,
            )

            mode = validate_mode(args.mode or config.mode or choose_mode())

            if mode == MODE_REMOTE:
                doors = client.list_doors(session)
                if not doors:
                    print("No doors were returned for this account.")
                    return 0

                print("")
                print("Available doors:")
                print_door_list(doors)

                if args.list_only:
                    return 0

                selected_door = choose_door(doors)
                unlock_method = args.unlock_method or config.unlock_method
                try:
                    result = client.unlock_door(session, selected_door.access_point_id, unlock_method)
                except RuntimeError:
                    print("")
                    print("Selected door diagnostics:")
                    print_door_diagnostics(selected_door)
                    print("")
                    print("Remote policy diagnostics:")
                    print_remote_policy_diagnostics(client, session)
                    raise

                print("")
                print(f"Unlocked: {selected_door.name}")
                print(f"Unlock method: {unlock_method}")
                if result.duration is not None:
                    print(f"Duration: {result.duration:.2f} seconds")
            elif mode == MODE_BLE_CENTRAL:
                if args.list_only:
                    raise RuntimeError("--list-only is only supported for remote mode.")

                ble_keys_path = resolve_ble_keys_path(config_path, config)
                result = asyncio.run(
                    run_ble_central_mode(
                        api_client=client,
                        session=session,
                        config=config,
                        ble_keys_path=ble_keys_path,
                        choose_reader=choose_reader,
                        override_user_id=args.ble_user_id,
                    )
                )

                print("")
                print(f"BLE central unlock sent to: {result.reader_name}")
                print(f"Reader serial: {result.reader_serial}")
                print(f"Reader address: {result.reader_address}")
                print(f"User ID: {result.user_id}")
                print(f"Payload size: {result.payload_size} bytes")
                print(f"BLE keys file: {ble_keys_path}")
                print(f"Created local BLE keys: {'yes' if result.created_new_key else 'no'}")
                print(f"Registered BLE key with backend: {'yes' if result.registered_key else 'no'}")
            else:
                if args.list_only:
                    raise RuntimeError("--list-only is only supported for remote mode.")

                ble_keys_path = resolve_ble_keys_path(config_path, config)
                print("")
                print("Starting BLE peripheral mode. Press Ctrl+C to stop.")
                print(f"BLE keys file: {ble_keys_path}")
                asyncio.run(
                    run_ble_peripheral_mode(
                        api_client=client,
                        session=session,
                        config=config,
                        ble_keys_path=ble_keys_path,
                        override_user_id=args.ble_user_id,
                    )
                )

        return 0
    except (RuntimeError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


def ensure_session(
    *,
    client: VerkadaPassClient,
    config: AppConfig,
    session_path: Path,
    force_login: bool,
    magic_link_override: str | None,
) -> SessionState:
    if not force_login:
        saved_session = load_session(session_path)
        if saved_session and prompt_yes_no(f"Reuse saved session from {session_path}?", default=True):
            return saved_session

    email, org_short_name = prompt_login_identity(config)
    magic_link_url = magic_link_override or config.magic_link_url

    if not magic_link_url:
        should_request = config.request_magic_link_first
        if should_request:
            print("Requesting a fresh magic link...")
            client.request_magic_link(email, org_short_name)
            print("Magic link requested. Paste the full URL from your email below.")

        magic_link_url = prompt_non_empty("Magic link URL")

    link = MagicLinkContext.from_url(magic_link_url)
    session = client.redeem_magic_link(link)
    save_session(session_path, session)
    print(f"Saved session to {session_path}")
    return session


def prompt_login_identity(config: AppConfig) -> tuple[str, str | None]:
    email = config.email or prompt_non_empty("Email")
    org_short_name = config.org_short_name
    if org_short_name is None:
        value = input("Org short name (optional): ").strip()
        org_short_name = value or None
    return email, org_short_name


def choose_door(doors: list[DoorRecord]) -> DoorRecord:
    while True:
        raw_value = prompt_non_empty("Select door number")
        try:
            index = int(raw_value)
        except ValueError:
            print("Please enter a number from the list.")
            continue

        if 1 <= index <= len(doors):
            return doors[index - 1]

        print("Door number is out of range.")


def choose_reader(readers: list[CentralReader]) -> CentralReader:
    print("")
    print("Nearby BLE readers:")
    for index, reader in enumerate(readers, start=1):
        details = [reader.address, f"serial={reader.reader_serial}"]
        if reader.rssi is not None:
            details.append(f"rssi={reader.rssi}")
        print(f"{index}. {reader.name} ({', '.join(details)})")

    while True:
        raw_value = prompt_non_empty("Select BLE reader number")
        try:
            index = int(raw_value)
        except ValueError:
            print("Please enter a number from the list.")
            continue

        if 1 <= index <= len(readers):
            return readers[index - 1]

        print("BLE reader number is out of range.")


def print_door_list(doors: list[DoorRecord]) -> None:
    for index, door in enumerate(doors, start=1):
        details: list[str] = []
        if door.access_controller_id:
            details.append(f"controller={door.access_controller_id}")
        if door.floor_id:
            details.append(f"floor={door.floor_id}")
        if door.schedule:
            schedule_states = door.schedule.distinct_states()
            if schedule_states:
                details.append(f"scheduleStates={'/'.join(schedule_states)}")
        suffix = f" ({', '.join(details)})" if details else ""
        print(f"{index}. {door.name} [{door.access_point_id}]{suffix}")


def print_door_diagnostics(door: DoorRecord) -> None:
    print(f"Door: {door.name} [{door.access_point_id}]")
    if door.access_controller_id:
        print(f"Controller ID: {door.access_controller_id}")
    if door.floor_id:
        print(f"Floor ID: {door.floor_id}")

    if door.schedule is None:
        print("Schedule: not returned in unlockables response.")
    else:
        print(f"Schedule window: {door.schedule.start_datetime} -> {door.schedule.end_datetime}")
        if door.schedule.events:
            for event in door.schedule.events:
                print(
                    "Schedule event: "
                    f"{event.door_permission_state} "
                    f"{event.start_datetime} -> {event.end_datetime}"
                )
        else:
            print("Schedule events: none")

    if isinstance(door.raw, dict):
        reader_peripherals = door.raw.get("readerPeripherals")
        if isinstance(reader_peripherals, list):
            print(f"Reader peripherals returned: {len(reader_peripherals)}")
        device_ios = door.raw.get("deviceIos")
        if isinstance(device_ios, list):
            print(f"Device IO entries returned: {len(device_ios)}")
    print("")


def print_remote_policy_diagnostics(client: VerkadaPassClient, session: SessionState) -> None:
    try:
        users = client.get_user_profiles(session)
    except RuntimeError as exc:
        print(f"User profile lookup failed: {exc}")
    else:
        matched_user = next(
            (item for item in users if str(item.get("userId") or "") == session.user_id),
            users[0] if users else None,
        )
        if matched_user is None:
            print("User profile: no users returned.")
        else:
            access_methods = matched_user.get("accessMethods")
            if isinstance(access_methods, dict) and access_methods:
                print("User access methods:")
                for scope, methods in access_methods.items():
                    if isinstance(methods, dict):
                        rendered = ", ".join(f"{name}={value}" for name, value in sorted(methods.items()))
                        print(f"- {scope}: {rendered}")
                    else:
                        print(f"- {scope}: {methods}")
            else:
                print("User access methods: not returned.")

    try:
        config = client.get_organization_config(session)
    except RuntimeError as exc:
        print(f"Organization config lookup failed: {exc}")
    else:
        print("Organization config:")
        if not config:
            print("- (empty)")
        else:
            for key in sorted(config):
                print(f"- {key}={config[key]}")


def choose_mode() -> str:
    print("")
    print("Available modes:")
    print("1. BLE central nearby unlock")
    print("2. BLE peripheral hands-free server")
    print("3. Remote unlock (disabled by policy)")

    while True:
        raw_value = prompt_non_empty("Select mode number")
        if raw_value == "1":
            return MODE_BLE_CENTRAL
        if raw_value == "2":
            return MODE_BLE_PERIPHERAL
        if raw_value == "3":
            return MODE_REMOTE
        print("Mode number is out of range.")


def prompt_non_empty(label: str) -> str:
    while True:
        value = input(f"{label}: ").strip()
        if value:
            return value
        print(f"{label} cannot be empty.")


def prompt_yes_no(message: str, *, default: bool) -> bool:
    suffix = "[Y/n]" if default else "[y/N]"
    value = input(f"{message} {suffix} ").strip().lower()
    if not value:
        return default
    if value in {"y", "yes"}:
        return True
    if value in {"n", "no"}:
        return False
    print("Please answer yes or no.")
    return prompt_yes_no(message, default=default)
