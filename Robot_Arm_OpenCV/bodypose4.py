import cv2
import mediapipe as mp
import math
import serial
import time
from collections import deque
import matplotlib.pyplot as plt
import os

# ================= SERIAL =================
ser = serial.Serial('/dev/cu.usbmodem1301', 9600, timeout=0.05)
time.sleep(2)

# ================= MEDIAPIPE =================
mp_pose = mp.solutions.pose
pose = mp_pose.Pose(
    static_image_mode=False,
    model_complexity=1,
    smooth_landmarks=True,
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5
)

# ================= CAMERA =================
cap = cv2.VideoCapture(0)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 960)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 540)

cv2.namedWindow("Vision Control", cv2.WINDOW_NORMAL)
cv2.resizeWindow("Vision Control", 900, 520)

# ================= FAILSAFE =================
last_pose_time = time.time()
POSE_TIMEOUT = 0.5
failsafe_sent = False
SAFE_ANGLE = 50

# landmark confidence
VIS_TH = 0.6

# ================= MPU / PID TELEMETRY =================
mpu_angle = None
pid_sp = None
pid_meas = None
pid_out = None

# ================= SERVO SEND CONTROL =================
last_send_time = 0
SEND_INTERVAL = 0.04   # 25 Hz
last_servo_angle = None

# ================= STABILITY FILTERS =================
sp_filtered = None
SP_ALPHA = 0.18        # lower = smoother (0.10–0.25)
SP_DEADBAND = 2        # degrees

# ================= GRAPH (LIVE) =================
plot_enabled = True
plt.ion()
fig, ax = plt.subplots()
ax.set_title("PID Live Graph (Servo4)")
ax.set_xlabel("Samples")
ax.set_ylabel("Angle")

N = 200
sp_hist = deque(maxlen=N)
meas_hist = deque(maxlen=N)
out_hist = deque(maxlen=N)

line_sp, = ax.plot([], [], label="Setpoint (SP)")
line_meas, = ax.plot([], [], label="Measured (MEAS)")
line_out, = ax.plot([], [], label="Servo Cmd (OUT)")
ax.legend(loc="upper right")


def update_plot():
    if not plot_enabled:
        return
    if len(sp_hist) < 5:
        return
    x = list(range(len(sp_hist)))
    line_sp.set_data(x, list(sp_hist))
    line_meas.set_data(x, list(meas_hist))
    line_out.set_data(x, list(out_hist))
    ax.relim()
    ax.autoscale_view()
    fig.canvas.draw()
    fig.canvas.flush_events()


# ================= FUNCTIONS =================

def calculate_angle(v1, v2):
    dot = v1[0] * v2[0] + v1[1] * v2[1]
    mag1 = math.hypot(v1[0], v1[1])
    mag2 = math.hypot(v2[0], v2[1])
    if mag1 == 0 or mag2 == 0:
        return 0
    v = dot / (mag1 * mag2)
    v = max(-1.0, min(1.0, v))
    return math.degrees(math.acos(v))


def map_to_servo(angle):
    servo = 30 + (angle / 180.0) * (100 - 30)
    return int(max(30, min(100, servo)))


def read_telemetry_from_arduino():
    global mpu_angle, pid_sp, pid_meas, pid_out
    try:
        line = ser.readline().decode(errors="ignore").strip()

        if line.startswith("MPU:"):
            mpu_angle = float(line.replace("MPU:", ""))

        elif line.startswith("SP:"):
            parts = line.split(",")
            for p in parts:
                if p.startswith("SP:"):
                    pid_sp = float(p.replace("SP:", ""))
                elif p.startswith("MEAS:"):
                    pid_meas = float(p.replace("MEAS:", ""))
                elif p.startswith("OUT:"):
                    pid_out = float(p.replace("OUT:", ""))
    except:
        pass


def send_safe_once():
    global failsafe_sent, last_servo_angle
    if (not failsafe_sent) or (last_servo_angle != SAFE_ANGLE):
        try:
            ser.write(f"V,{SAFE_ANGLE}\n".encode())
        except:
            pass
        last_servo_angle = SAFE_ANGLE
        failsafe_sent = True


def close_graph_only():
    global plot_enabled
    plot_enabled = False
    try:
        plt.close('all')
    except:
        pass


def quit_everything():
    # guarantee stop on any environment (IDE/Jupyter/Terminal)
    try:
        ser.write(b"V,50\n")
    except:
        pass

    try:
        cap.release()
    except:
        pass

    try:
        cv2.destroyAllWindows()
        # let OpenCV process the destroy message
        cv2.waitKey(1)
    except:
        pass

    try:
        plt.close('all')
    except:
        pass

    try:
        ser.close()
    except:
        pass

    # Hard exit (this fixes "camera still running")
    os._exit(0)


# ================= MAIN LOOP =================
print("Press 'g' to close graph only | Press 'q' to quit everything")

while cap.isOpened():
    success, frame = cap.read()
    if not success:
        continue

    frame = cv2.flip(frame, 1)

    # ===== ROI crop =====
    h0, w0, _ = frame.shape
    crop_scale = 0.70
    cw = int(w0 * crop_scale)
    ch = int(h0 * crop_scale)
    x1 = (w0 - cw) // 2
    y1 = (h0 - ch) // 2
    roi = frame[y1:y1 + ch, x1:x1 + cw]

    rgb = cv2.cvtColor(roi, cv2.COLOR_BGR2RGB)
    results = pose.process(rgb)

    human_angle = None
    servo_angle = None

    # ================= POSE =================
    
    if results.pose_landmarks:
        h, w, _ = roi.shape
        lm = results.pose_landmarks.landmark

        sh = lm[mp_pose.PoseLandmark.LEFT_SHOULDER]
        el = lm[mp_pose.PoseLandmark.LEFT_ELBOW]
        wr = lm[mp_pose.PoseLandmark.LEFT_WRIST]

        good_points = (sh.visibility > VIS_TH and el.visibility > VIS_TH and wr.visibility > VIS_TH)

        if not good_points:
            cv2.putText(roi, "LANDMARKS NOT STABLE - SAFE MODE",
                        (20, 60), cv2.FONT_HERSHEY_SIMPLEX,
                        1.0, (0, 0, 255), 3)
            send_safe_once()

        else:
            last_pose_time = time.time()
            failsafe_sent = False

            sh_p = (int(sh.x * w), int(sh.y * h))
            el_p = (int(el.x * w), int(el.y * h))
            wr_p = (int(wr.x * w), int(wr.y * h))

            v1 = (el_p[0] - sh_p[0], el_p[1] - sh_p[1])
            v2 = (wr_p[0] - el_p[0], wr_p[1] - el_p[1])

            angle = calculate_angle(v1, v2)
            human_angle = int(180 - angle)
            raw_servo = map_to_servo(human_angle)

            # smooth setpoint
            if sp_filtered is None:
                sp_filtered = raw_servo
            sp_filtered = (1 - SP_ALPHA) * sp_filtered + SP_ALPHA * raw_servo
            servo_angle = int(round(sp_filtered))
            servo_angle = max(30, min(100, servo_angle))

            # Draw arm
            cv2.line(roi, sh_p, el_p, (0, 255, 0), 3)
            cv2.line(roi, el_p, wr_p, (255, 0, 0), 3)
            cv2.circle(roi, el_p, 8, (0, 0, 255), -1)

            # send setpoint (rate limited + deadband)
            now = time.time()
            if (now - last_send_time) > SEND_INTERVAL:
                if last_servo_angle is None or abs(servo_angle - last_servo_angle) >= SP_DEADBAND:
                    try:
                        ser.write(f"V,{servo_angle}\n".encode())
                    except:
                        pass
                    last_servo_angle = servo_angle
                    last_send_time = now

    # ================= FAILSAFE (pose lost) =================
    if time.time() - last_pose_time > POSE_TIMEOUT:
        cv2.putText(roi, "POSE LOST - FAILSAFE ACTIVE",
                    (20, 60), cv2.FONT_HERSHEY_SIMPLEX,
                    1.0, (0, 0, 255), 3)
        send_safe_once()

    # ================= READ TELEMETRY =================
    for _ in range(3):
        read_telemetry_from_arduino()

    # ================= UI TEXT (bigger) =================
    y = 35
    if human_angle is not None:
        cv2.putText(roi, f"Human Arm Angle: {human_angle} deg",
                    (20, y), cv2.FONT_HERSHEY_SIMPLEX,
                    1.0, (0, 0, 255), 3)
        y += 35

    if servo_angle is not None:
        cv2.putText(roi, f"Setpoint(SP): {servo_angle} (30-100)",
                    (20, y), cv2.FONT_HERSHEY_SIMPLEX,
                    1.0, (0, 0, 255), 3)
        y += 35

    if pid_meas is not None:
        cv2.putText(roi, f"Measured(MEAS): {pid_meas:.2f}",
                    (20, y), cv2.FONT_HERSHEY_SIMPLEX,
                    1.0, (0, 0, 255), 3)
        y += 35

    if pid_out is not None:
        cv2.putText(roi, f"ServoCmd(OUT): {pid_out:.2f}",
                    (20, y), cv2.FONT_HERSHEY_SIMPLEX,
                    1.0, (0, 0, 255), 3)

    # ================= GRAPH UPDATE =================
    if pid_sp is not None and pid_meas is not None and pid_out is not None:
        sp_hist.append(pid_sp)
        meas_hist.append(pid_meas)
        out_hist.append(pid_out)
        update_plot()

    cv2.imshow("Vision Control", roi)

    # ================= KEY CONTROL =================
    key = cv2.waitKey(1) & 0xFF
    if key == ord('g'):
        close_graph_only()
    elif key == ord('q'):
        quit_everything()

# If loop ends unexpectedly:
quit_everything()
