# TrueMPG Android app — build & install

A native Kotlin/Compose app that pairs to your OBDLink MX+ over Bluetooth and
does live MPG (speed-density, tuned for the 2.7L EcoBoost), a live dashboard,
DTC read/clear, and on-phone trip logging.

## Get the APK via GitHub Actions (no Android Studio needed)

1. Create a GitHub repo and push this whole `TrueMPG` folder to it (the repo
   must contain both `android/` and `.github/workflows/android-build.yml`).

   ```bash
   cd C:\Users\Maverick\Desktop\TrueMPG
   git init
   git add .
   git commit -m "TrueMPG: OBD tools + Android app"
   git branch -M main
   git remote add origin https://github.com/<you>/TrueMPG.git
   git push -u origin main
   ```

2. On GitHub → **Actions** tab → the **Build TrueMPG APK** workflow runs
   automatically on push (or click **Run workflow**).

3. When it finishes (green check), open the run → **Artifacts** →
   download **TrueMPG-debug-apk** → unzip → `app-debug.apk`.

4. Copy the APK to your phone. In the phone's file manager, tap it and allow
   "Install unknown apps" for that app when prompted.

## First run on the phone

1. Pair the OBDLink MX+ in Android **Settings → Bluetooth** first (PIN usually
   `1234` or `0000` if asked; the MX+ must be plugged into the truck, key ON).
2. Open **TrueMPG**, grant the Bluetooth permission.
3. **Connect** tab → Refresh → tap the OBDLink → wait for "Connected".
4. **Drive** tab → Start trip. Real numbers appear once the engine is running
   and you're moving.
5. **Codes** tab → read/clear DTCs. **Trips** tab → saved trip history.

## Calibrating MPG (important)

This engine has no MAF sensor, so fuel is *estimated* by speed-density with a
volumetric-efficiency (VE) factor, default **0.85** (Connect tab slider).
To calibrate: run a full tank logging trips, compare the app's total gallons to
the pump, then set `VE = 0.85 × (app_gallons ÷ pump_gallons)`.

## Notes / limits
- Debug APK = self-signed, fine for personal use; Play Store would need a
  release keystore (can be added later).
- Works on the HS-CAN / standard OBD-II bus (the only bus the 2020 gateway
  exposes). Door locks / body modules remain out of reach — see REACHABILITY.md.
- If the first CI build fails, open the failed step's log — it names the exact
  version/line to fix.
