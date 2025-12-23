#include <Servo.h>
#include <SoftwareSerial.h>
#include <Wire.h>
#include <MPU6050_light.h>

// ================= PID FOR SERVO4 (VISION ONLY) =================
float Kp = 1.6;
float Ki = 0.05;
float Kd = 0.35;

float pid_integral = 0;
float pid_prev_error = 0;
unsigned long pid_last_time = 0;

const float INTEGRAL_CLAMP = 80.0;    // anti-windup
const float PID_DEADBAND = 0.6;       // degrees (reduces jitter)


// ================= MPU =================
MPU6050 mpu(Wire);
unsigned long timer = 0;

float initialPitch = 50.0;
float pitch_angle;

// MPU smoothing
float filteredPitch = 0;
const float alpha = 0.90;   // 0.8–0.95 (higher = smoother)
const float DEAD_BAND = 1.0; // degrees

// ================= CONSTANTS =================

const int numServos = 6;
const int maxConfigurations = 10;
const int stepDelay = 15;
const int stepSize = 1;

// Servo pins
const int servoPins[numServos] = {2, 3, 4, 5, 6, 7};
const int SERVO4_INDEX = 3;

// Servo limits for vision
const int SERVO4_MIN = 30;
const int SERVO4_MAX = 100;
const int SERVO4_SAFE = 50;

// ================= STORAGE =================
Servo servos[numServos];
int savedConfigurations[maxConfigurations][numServos];
int currentServoPositions[numServos] = {64, 90, 90, 50, 49, 66};
int initialServoPositions[numServos] = {64, 90, 90, 50, 49, 66};

int configCount = 0;
bool isPlaying = false;
bool loopPlayback = false;
bool stopPlaying = false;
int currentPoseIndex = 0;

// ================= BLUETOOTH =================
SoftwareSerial BTSerial(10, 11);

// ================= VISION OVERRIDE =================
bool visionActive = false;
unsigned long lastVisionTime = 0;
const unsigned long VISION_TIMEOUT = 600;

int targetServo4 = SERVO4_SAFE;
float smoothServo4 = SERVO4_SAFE;
const float visionAlpha = 0.2;

// ================= SETUP =================
void setup() {
  Serial.begin(9600);
  BTSerial.begin(9600);
  Wire.begin();
  byte status = mpu.begin();
  Serial.print(F("MPU6050 status: "));
  Serial.println(status);

  while (status != 0) {
    // Stop everything if could not connect to MPU6050
    Serial.println("Error: Could not connect to MPU6050.");
    delay(1000);
  }

  for (int i = 0; i < numServos; i++) {
    servos[i].attach(servoPins[i]);
    servos[i].write(currentServoPositions[i]);
  }

 
  Serial.println(F("Calculating offsets, do not move MPU6050"));
  delay(1000);
  mpu.calcOffsets();  // Gyro and accelerometer calibration
  Serial.println("Done!\n");
 
  pitch_angle = initialPitch;

  Serial.println("AUST_ARM started. Waiting for commands...");
}

// ================= LOOP =================
void loop() {
  handleBluetooth();
  handleVisionOverride();

   // ----- PLAYBACK HANDLER (FIX) -----
  if (isPlaying && !stopPlaying) {
    if (loopPlayback) {
      playPosesInLoop();
    } else {
      playNextPose();
    }
  }

 
  if (millis() - timer >= 100) {   // print at 10 Hz
    float mpuPitch = HandleMPU();
    Serial.print("MPU:");
    Serial.println(mpuPitch);
    timer = millis();
  }
}

// ================= BLUETOOTH =================
void handleBluetooth() {
  if (!BTSerial.available()) return;

  String input = BTSerial.readStringUntil('\n');
  input.trim();

  if (input.equalsIgnoreCase("S")) saveCurrentPose();
  else if (input.equalsIgnoreCase("P")) startPlayingPoses();
  else if (input.equalsIgnoreCase("R")) resetPoses();
  else if (input.equalsIgnoreCase("St")) stopPlayingPoses();
  else if (input.equalsIgnoreCase("D"))
    {
      stopPlayingPoses();
      resetPoses();
      setToDefault();
    }
  else if (input.equalsIgnoreCase("LoopON")) loopPlayback = true;
  else if (input.equalsIgnoreCase("LoopOFF")) loopPlayback = false;
  else if (input.indexOf(',') > 0) processServoCommand(input);
}

// ================= SERVO COMMAND =================
void processServoCommand(String cmd) {
  int comma = cmd.indexOf(',');
  int idx = cmd.substring(0, comma).toInt() - 1;
  int angle = cmd.substring(comma + 1).toInt();

  if (idx < 0 || idx >= numServos) return;

  // Ignore servo 4 if vision active
  if (idx == SERVO4_INDEX && visionActive) return;

  moveToPositionSmoothly(idx, angle);
}

// ================= VISION SERIAL =================
void serialEvent() {
  while (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();

    if (cmd.startsWith("V,")) {
      targetServo4 = constrain(cmd.substring(2).toInt(), SERVO4_MIN, SERVO4_MAX);
      visionActive = true;
      lastVisionTime = millis();
    }
  }
}

// ================= VISION OVERRIDE =================
void handleVisionOverride() {
  if (!visionActive) return;

  // Timeout handling
  if (millis() - lastVisionTime > VISION_TIMEOUT) {
    targetServo4 = SERVO4_SAFE;
    visionActive = false;

    // Reset PID when vision stops
    pid_integral = 0;
    pid_prev_error = 0;
    return;
  }

  // ====== MEASURE (MPU) ======
  float meas = HandleMPU();           // already filtered pitch output
  meas = constrain(meas, SERVO4_MIN, SERVO4_MAX);

  // If direction is opposite, invert error:
  // float error = (targetServo4 - meas);        // normal
  // float error = (meas - targetServo4);        // inverted (use this if needed)
  float error = (targetServo4 - meas);

  // Deadband to kill tiny fluctuations
  if (fabs(error) < PID_DEADBAND) error = 0;

  // ====== PID timing ======
  unsigned long now = millis();
  if (pid_last_time == 0) pid_last_time = now;
  float dt = (now - pid_last_time) / 1000.0;
  if (dt <= 0) dt = 0.02;   // fallback
  pid_last_time = now;

  // ====== PID compute ======
  pid_integral += error * dt;
  pid_integral = constrain(pid_integral, -INTEGRAL_CLAMP, INTEGRAL_CLAMP);

  float derivative = (error - pid_prev_error) / dt;
  pid_prev_error = error;

  float pid = (Kp * error) + (Ki * pid_integral) + (Kd * derivative);

  // ====== Apply output ======
  // PID output is "correction", so drive servo command around current position
  float cmd = currentServoPositions[SERVO4_INDEX] + pid;

  // Extra smoothing (optional but helps a lot)
  const float cmdAlpha = 0.25;  // 0.15–0.35
  smoothServo4 = smoothServo4 * (1.0 - cmdAlpha) + cmd * cmdAlpha;

  smoothServo4 = constrain(smoothServo4, SERVO4_MIN, SERVO4_MAX);

  servos[SERVO4_INDEX].write((int)smoothServo4);
  currentServoPositions[SERVO4_INDEX] = (int)smoothServo4;

  // ====== Debug print for Python graph ======
  // keep your existing MPU print in loop unchanged; this adds more lines:
  Serial.print("SP:"); Serial.print(targetServo4);
  Serial.print(",MEAS:"); Serial.print(meas, 2);
  Serial.print(",OUT:"); Serial.println(smoothServo4, 2);
}


// ================= MPU =================

float HandleMPU() {
  static unsigned long lastUpdate = 0;
  static float filteredPitch = 0;

  mpu.update();

  float rawPitch = mpu.getAngleY();
  float pitch = initialPitch + rawPitch;

  // Low-pass filter (smooth output)
  const float alpha = 0.90;   // higher = smoother
  filteredPitch = alpha * filteredPitch + (1.0 - alpha) * pitch;

  // Limit update rate (optional but recommended)
  if (millis() - lastUpdate >= 20) {  // 50 Hz
    lastUpdate = millis();
    return filteredPitch;
  }

  return filteredPitch; // always return latest value
}

// ================= ORIGINAL FUNCTIONS =================
void moveToPositionSmoothly(int i, int target) {
  target = constrain(target, 0, 180);
  int current = currentServoPositions[i];

  int step = (target > current) ? stepSize : -stepSize;
  for (int pos = current; pos != target; pos += step) {
    servos[i].write(pos);
    delay(stepDelay);
  }
  currentServoPositions[i] = target;
}

void saveCurrentPose() {
  if (configCount >= maxConfigurations) return;
  for (int i = 0; i < numServos; i++)
    savedConfigurations[configCount][i] = currentServoPositions[i];
  configCount++;
}

void startPlayingPoses() {
  if (configCount == 0) {
    Serial.println("No poses saved, nothing to play.");
    return;
  }

  Serial.println("Playback of saved poses started...");
  isPlaying = true;
  stopPlaying = false;
  currentPoseIndex = 0;
}

void playNextPose() {
  if (currentPoseIndex < configCount) {
    Serial.println("Playing pose " + String(currentPoseIndex + 1));
    for (int i = 0; i < numServos; i++) {
      moveToPositionSmoothly(i, savedConfigurations[currentPoseIndex][i]);
    }
    delay(100);
    currentPoseIndex++;
  } else {
    isPlaying = false;
    Serial.println("Playback of poses finished.");
  }
}

void playPosesInLoop() {
  playNextPose();
  if (!isPlaying) {
    currentPoseIndex = 0;
    isPlaying = true;
  }
}

void stopPlayingPoses() {
  stopPlaying = true;
  isPlaying = false;
  Serial.println("Playback of poses stopped.");
}

void resetPoses() {
  configCount = 0;
  isPlaying = false;
  loopPlayback = false;
  currentPoseIndex = 0;
  Serial.println("All saved poses have been deleted.");
}

void setToDefault() {
  for (int i = 0; i < numServos; i++)
    moveToPositionSmoothly(i, initialServoPositions[i]);
}

