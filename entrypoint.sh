#!/bin/bash
set -e

# Configuration
XVFB_DISPLAY=":2"
SCREEN_RESOLUTION="1280x720x24"
JAR_FILE="app.jar"
JAVA_OPTS="-Xmx1g -Djava.awt.headless=false"

mkdir -p /var/www/html/dash

echo "Starting Nginx..."
nginx

echo "Starting Xvfb on display ${XVFB_DISPLAY}..."
Xvfb ${XVFB_DISPLAY} -screen 0 ${SCREEN_RESOLUTION} -ac &
XVFB_PID=$!

export DISPLAY=${XVFB_DISPLAY}
sleep 2

echo "Starting ffmpeg screen recording..."
ffmpeg -video_size 1280x720 -framerate 30 -f x11grab -i ${DISPLAY}.0 \
  -pix_fmt yuv420p \
  -c:v libx264 -preset ultrafast -profile:v main -level 3.1 \
  -g 60 -keyint_min 60 \
  -f dash \
  -window_size 5 -extra_window_size 5 -remove_at_exit 1 \
  -seg_duration 1 \
  -streaming 1 -use_timeline 1 -use_template 1 \
  /var/www/html/dash/manifest.mpd &
FFMPEG_PID=$!

echo "Starting Java application..."
java ${JAVA_OPTS} -jar ${JAR_FILE}

echo "Cleaning up..."
kill ${FFMPEG_PID} || true
kill ${XVFB_PID} || true
