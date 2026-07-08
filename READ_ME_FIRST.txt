TRUEMPG - your truck app (built for the OBDLink MX+)
====================================================

WHAT'S IN THIS FOLDER
  truempg_full.py .............. the app (do not edit)
  1_FIRST_TIME_SETUP.bat ....... run ONCE to install add-ons
  2_START_DEMO.bat ............. try it now, no adapter needed
  3_START_MY_TRUCK.bat ......... live on your F-150 via MX+
  3b_START_MY_TRUCK_SLOWER.bat . use if #3 is flaky (steadier Bluetooth)
  4_START_FOR_PHONE.bat ........ view the dashboard on your phone
  guided_diagnostics.py ........ standalone diagnostics engine (optional)

ONE-TIME SETUP
  1. Install Python from https://www.python.org/downloads/
     -> CHECK the box "Add python.exe to PATH" on the first screen.
  2. Put this whole folder on your Desktop.
  3. Double-click  1_FIRST_TIME_SETUP  (installs add-ons). Close when done.

TRY IT NOW (no adapter)
  Double-click  2_START_DEMO  -> browser opens to 127.0.0.1:5000

WHEN THE MX+ ARRIVES
  1. Plug MX+ into the truck's OBD port (under dash, driver side).
  2. Ignition ON (engine running is best).
  3. Press the MX+ button; pair it in Windows Settings > Bluetooth.
  4. Find its COM port: Bluetooth settings > More Bluetooth options
     > COM Ports tab > the "Outgoing" OBDLink port (e.g. COM5).
  5. Right-click  3_START_MY_TRUCK  > Edit, change COM5 to your port, Save.
  6. Double-click  3_START_MY_TRUCK.  Dashboard shows your real truck.
     (If flaky, use 3b instead.)

VIEW ON YOUR PHONE
  Double-click  4_START_FOR_PHONE. It prints this PC's IP (192.168.x.x).
  On your phone (same wifi) open:  that-IP:5000

FORSCAN (the control side - separate tool)
  Install FORScan from https://forscan.org on this Windows PC.
  Use it for maintenance mode / module work. Your app handles the
  read/log/learn/diagnose side. Same MX+ serves both.

STOP THE APP
  Close the black window.
