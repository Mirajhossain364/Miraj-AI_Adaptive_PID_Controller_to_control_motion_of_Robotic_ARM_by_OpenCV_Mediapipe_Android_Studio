import serial
import csv
import time

# ================= CONFIG =================
SERIAL_PORT = 'COM6'       # Change to your Arduino port
BAUD_RATE = 9600
CSV_FILENAME = 'mpu_log.csv'

# ================= SERIAL SETUP =================
ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1)
time.sleep(2)  # Wait for Arduino to reset

# ================= CSV SETUP =================
with open(CSV_FILENAME, mode='w', newline='') as file:
    writer = csv.writer(file)
    # Write header
    writer.writerow(['Time_ms', 'MPU', 'Target_Angle', 'Error'])

    print("Logging started. Press Ctrl+C to stop.")
    start_time = time.time()
    try:
        while True:
            line = ser.readline().decode(errors='ignore').strip()
            if not line:
                continue

            # Example line: "51.23,50,1.23"
            try:
                parts = line.split(',')
                if len(parts) != 3:
                    continue

                mpu_value = float(parts[0].strip())
                target_value = float(parts[1].strip())
                error_value = float(parts[2].strip())
                time_ms = int((time.time() - start_time) * 1000)

                # Write row to CSV
                writer.writerow([time_ms, mpu_value, target_value, error_value])
                file.flush()  # Ensure it's written to disk immediately

                # Optional: print to console
                print(time_ms, mpu_value, target_value, error_value)

            except Exception as e:
                # Skip malformed lines
                print("Parsing error:", e)
                continue

    except KeyboardInterrupt:
        print("\nLogging stopped.")

ser.close()

