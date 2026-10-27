# 시연 결과 2026-10-27 17:56

A=emulator-5556 B=emulator-5558 speed=60 km/h  확인 10/10

| 장면 | 기대 | 관측 |
|---|---|---|
| 5 (A 발행) | A: vehicle event LOW_ENERGY 발행 | 10-27 17:53:48.533 I/DongHaeng(31258): vehicle event LOW_ENERGY LowEnergy(kind=EV, pct=12) published=true |
| 5 (B 음성) | B: voice: speak LOW_ENERGY | 10-27 01:53:47.829 I/DongHaeng(20053): voice: speak LOW_ENERGY priority=NORMAL len=22 |
| 6 | B: voice: speak PASSENGER_MESSAGE | 10-27 01:54:12.532 I/DongHaeng(20053): voice: speak PASSENGER_MESSAGE priority=NORMAL len=27 |
| 8 (A 발행) | A: ROUTE_CHANGED published=true | 4:27.923 I/DongHaeng(31258): routesync: ROUTE_CHANGED published=true Place(경포해변, (37.7**, 128.9**), poiId=-) waypoints=1 |
| 8 (B 제안) | B: routesync: proposal … 따라갈까요 | /DongHaeng(20053): routesync: proposal prp_…7A dest=Place(경포해변, (37.7**, 128.9**), poiId=-) waypoints=1 autoFollow=false |
| 8 (B 수락) | B: requestRouteWithWaypoints + ACK | 10-27 01:54:35.546 I/DongHaeng(20053): routesync: accepted prp_…7A local=false navi=true ack=true |
| 7 (B 도착) | B: FakeNavi waypoint arrived → WAYPOINT_ARRIVED 발행 | 10-27 01:55:55.551 D/DongHaeng(20053): voice: skip own WAYPOINT_ARRIVED |
| 7 (A 수신) | A: 그룹 이벤트 WAYPOINT_ARRIVED 수신(피드) | 10-27 17:55:56.266 I/DongHaeng(31258): voice: speak WAYPOINT_ARRIVED priority=LOW len=23 |
| 9 (A 발행) | A: vehicle event HARD_BRAKE | 10-27 17:56:00.774 D/DongHaeng(31258): voice: skip own HARD_BRAKE |
| 9 (B 음성) | B: voice: speak HARD_BRAKE | 10-27 01:56:00.054 I/DongHaeng(20053): voice: speak HARD_BRAKE priority=HIGH len=26 |
