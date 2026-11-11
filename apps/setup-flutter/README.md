# AJ Setup — installer app (D-015)

For installers only. Sign in once with a Music Assistant **admin** user (a built-in MA user, e.g. `installer`, role Admin –
Home Assistant sign-in is not supported in the app).

- **Overview**: server status, panels online, what needs attention.
- **Rooms**: rename (also the AirPlay name), AirPlay receiver, max volume, delay trim, test sound, restart player, panel log, update panel app.
- **Groups**: create / rename / change rooms / AirPlay / delete.
- **Users & access**: add users, role, which rooms each user may use, reset password, delete.
- **Logs**: Music Assistant log + diagnostics, every panel's log, network checks, one-tap full report to share.

Panel functions use AJ Panel's LAN API (port 8766, AJ Panel ≥ 0.3.1); the panel checks the installer's MA token (admin only).
Built by GitHub Actions together with the phone app (`.github/workflows/phone-flutter.yml`).
