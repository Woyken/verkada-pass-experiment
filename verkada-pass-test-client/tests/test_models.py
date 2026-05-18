from __future__ import annotations

import unittest

from verkada_pass_test_client.models import DoorRecord, DoorSchedule


class DoorModelsTest(unittest.TestCase):
    def test_door_schedule_parses_events(self) -> None:
        schedule = DoorSchedule.from_api(
            {
                "doorId": "door-123",
                "startDateTime": "2026-01-01T08:00:00Z",
                "endDateTime": "2026-01-01T18:00:00Z",
                "events": [
                    {
                        "doorPermissionState": "allowed",
                        "startDateTime": "2026-01-01T08:00:00Z",
                        "endDateTime": "2026-01-01T12:00:00Z",
                    },
                    {
                        "doorPermissionState": "denied",
                        "startDateTime": "2026-01-01T12:00:00Z",
                        "endDateTime": "2026-01-01T18:00:00Z",
                    },
                ],
            }
        )

        self.assertEqual(schedule.door_id, "door-123")
        self.assertEqual(schedule.distinct_states(), ["allowed", "denied"])
        self.assertEqual(schedule.events[0].door_permission_state, "allowed")

    def test_door_record_accepts_attached_schedule(self) -> None:
        schedule = DoorSchedule.from_api(
            {
                "doorId": "door-123",
                "startDateTime": "2026-01-01T08:00:00Z",
                "endDateTime": "2026-01-01T18:00:00Z",
                "events": [],
            }
        )

        door = DoorRecord.from_api(
            {
                "doorId": "door-123",
                "name": "Front Door",
                "accessControllerId": "controller-1",
                "floorId": "floor-1",
            },
            schedule=schedule,
        )

        self.assertEqual(door.access_point_id, "door-123")
        self.assertIs(door.schedule, schedule)

