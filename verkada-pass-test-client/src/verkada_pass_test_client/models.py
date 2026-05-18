from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, urlparse


def _first_value(mapping: dict[str, list[str]], key: str) -> str | None:
    values = mapping.get(key)
    if not values:
        return None
    value = values[0].strip()
    return value or None


@dataclass(slots=True)
class MagicLinkContext:
    source_url: str
    magic_token: str
    user_email: str
    org_short_name: str | None = None
    entity_id: str | None = None
    shard_name: str | None = None

    @classmethod
    def from_url(cls, url: str) -> "MagicLinkContext":
        parsed = urlparse(url.strip())
        query = parse_qs(parsed.query)

        magic_token = _first_value(query, "magicToken")
        user_email = _first_value(query, "userEmail")
        org_short_name = _first_value(query, "orgShortName")
        entity_id = _first_value(query, "entityId")
        shard_name = _first_value(query, "shard")

        if not magic_token:
            raise ValueError("The magic link URL is missing the magicToken query parameter.")
        if not user_email:
            raise ValueError("The magic link URL is missing the userEmail query parameter.")

        return cls(
            source_url=url.strip(),
            magic_token=magic_token,
            user_email=user_email,
            org_short_name=org_short_name,
            entity_id=entity_id,
            shard_name=shard_name,
        )


@dataclass(slots=True)
class SessionState:
    organization_id: str
    user_id: str
    user_token: str
    email: str
    shard_domain: str | None = None
    shard_name: str | None = None
    org_short_name: str | None = None

    @classmethod
    def from_login_response(
        cls,
        data: dict[str, Any],
        *,
        org_short_name: str | None = None,
    ) -> "SessionState":
        user_shard = data.get("userShard") or data.get("shard") or {}
        host_metadata = user_shard.get("host_metadata") or user_shard.get("hostMetadata") or {}
        shard_domain = host_metadata.get("domain") or user_shard.get("domain")
        shard_name = user_shard.get("name") or user_shard.get("shardName")

        organization_id = str(data["organizationId"])
        user_id = str(data["userId"])
        user_token = str(data["userToken"])
        email = str(data["email"])

        return cls(
            organization_id=organization_id,
            user_id=user_id,
            user_token=user_token,
            email=email,
            shard_domain=str(shard_domain) if shard_domain else None,
            shard_name=str(shard_name) if shard_name else None,
            org_short_name=org_short_name,
        )

    def auth_headers(self) -> dict[str, str]:
        return {
            "X-Verkada-Organization-Id": self.organization_id,
            "X-Verkada-User-Id": self.user_id,
            "X-Verkada-Auth": self.user_token,
        }

    def to_toml_dict(self) -> dict[str, Any]:
        return {
            "organization_id": self.organization_id,
            "user_id": self.user_id,
            "user_token": self.user_token,
            "email": self.email,
            "shard_domain": self.shard_domain or "",
            "shard_name": self.shard_name or "",
            "org_short_name": self.org_short_name or "",
        }

    @classmethod
    def from_toml_dict(cls, data: dict[str, Any]) -> "SessionState":
        return cls(
            organization_id=str(data["organization_id"]),
            user_id=str(data["user_id"]),
            user_token=str(data["user_token"]),
            email=str(data["email"]),
            shard_domain=str(data.get("shard_domain") or "") or None,
            shard_name=str(data.get("shard_name") or "") or None,
            org_short_name=str(data.get("org_short_name") or "") or None,
        )


@dataclass(slots=True)
class DoorScheduleEvent:
    door_permission_state: str
    start_datetime: str
    end_datetime: str

    @classmethod
    def from_api(cls, data: dict[str, Any]) -> "DoorScheduleEvent":
        return cls(
            door_permission_state=str(data["doorPermissionState"]),
            start_datetime=str(data["startDateTime"]),
            end_datetime=str(data["endDateTime"]),
        )


@dataclass(slots=True)
class DoorSchedule:
    door_id: str
    start_datetime: str
    end_datetime: str
    events: list[DoorScheduleEvent]
    raw: dict[str, Any] | None = None

    @classmethod
    def from_api(cls, data: dict[str, Any]) -> "DoorSchedule":
        door_id = str(data["doorId"])
        events_raw = data.get("events")
        if not isinstance(events_raw, list):
            raise ValueError("Door schedule payload did not include an events array.")

        events = [DoorScheduleEvent.from_api(item) for item in events_raw if isinstance(item, dict)]
        return cls(
            door_id=door_id,
            start_datetime=str(data["startDateTime"]),
            end_datetime=str(data["endDateTime"]),
            events=events,
            raw=data,
        )

    def distinct_states(self) -> list[str]:
        states: list[str] = []
        for event in self.events:
            if event.door_permission_state not in states:
                states.append(event.door_permission_state)
        return states


@dataclass(slots=True)
class DoorRecord:
    access_point_id: str
    name: str
    access_controller_id: str | None = None
    floor_id: str | None = None
    schedule: DoorSchedule | None = None
    raw: dict[str, Any] | None = None

    @classmethod
    def from_api(
        cls,
        data: dict[str, Any],
        *,
        schedule: DoorSchedule | None = None,
    ) -> "DoorRecord":
        access_point_id = str(data.get("doorId") or data.get("accessPointId") or data.get("id") or "")
        if not access_point_id:
            raise ValueError("Door payload did not include a usable access-point ID.")

        return cls(
            access_point_id=access_point_id,
            name=str(data.get("name") or access_point_id),
            access_controller_id=(str(data["accessControllerId"]) if data.get("accessControllerId") else None),
            floor_id=(str(data["floorId"]) if data.get("floorId") else None),
            schedule=schedule,
            raw=data,
        )


@dataclass(slots=True)
class UnlockResult:
    duration: float | None = None
    raw: dict[str, Any] | None = None
