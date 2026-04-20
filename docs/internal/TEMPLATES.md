# TEMPLATES

Mirror of `app/src/main/res/values/strings.xml`. Update this markdown first, then sync to XML. The app reads only from `strings.xml`.

## Setup flow (Phase 1 Step 2)

| Resource ID | User-facing string |
|---|---|
| `setup_title` | SeekerZero setup |
| `setup_welcome_header` | Welcome |
| `setup_welcome_body` | SeekerZero is a remote control for Agent Zero over your tailnet. Scan the QR shown on your Agent Zero server to link this device. |
| `setup_scan_cta` | Scan QR |
| `setup_manual_cta` | Can't scan? Enter host manually |
| `setup_manual_header` | Manual entry |
| `setup_manual_body` | Type the tailnet host of your Agent Zero server. Include the port if it is not 80. |
| `setup_manual_field_host` | Tailnet host |
| `setup_manual_field_client` | Client ID (optional) |
| `setup_manual_submit` | Continue |
| `setup_manual_back` | Back |
| `setup_verifying_body` | Contacting Agent Zero… |
| `setup_health_failed_header` | Couldn't reach Agent Zero |
| `setup_health_failed_retry` | Retry |
| `setup_health_failed_start_over` | Start over |
| `setup_notification_header` | Notifications |
| `setup_notification_body` | SeekerZero uses notifications to alert you when Agent Zero has a new approval gate. You can skip this — the app will still work, just without alerts. |
| `setup_notification_allow` | Allow |
| `setup_notification_skip` | Skip |
| `setup_battery_header` | Battery optimization |
| `setup_battery_body` | To keep the connection to Agent Zero alive in the background, SeekerZero should be exempt from battery optimization. You can skip this now and change it later. |
| `setup_battery_open_settings` | Open settings |
| `setup_battery_skip` | Skip |
| `setup_done_primary` | Linked. |
| `setup_done_secondary` | Opening SeekerZero… |

## Main scaffold (Phase 1 Step 2)

| Resource ID | User-facing string |
|---|---|
| `tab_approvals` | Approvals |
| `tab_tasks` | Tasks |
| `tab_cost` | Cost |
| `tab_diagnostics` | Diagnostics |
| `stub_approvals_body` | Pending approval gates will appear here. |
| `stub_tasks_body` | Scheduled tasks from Agent Zero will appear here. |
| `stub_cost_body` | Daily / 7-day / 30-day cost rollups will appear here. |
| `stub_diagnostics_body` | Connection, fleet health, and build info will appear here. |
