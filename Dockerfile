# Stage 3: Final runtime image (Java 21 on Ubuntu Jammy)
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && \
    apt-get install -y \
    curl wget gnupg ca-certificates build-essential \
    xvfb x11-utils imagemagick ffmpeg nginx \
    libx11-xcb1 libxcomposite1 libxcursor1 libxdamage1 libxrandr2 libxss1 libxtst6 \
    libnss3 libatk-bridge2.0-0 libgtk-3-0 libgbm1 tzdata && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Set timezone ke Asia/Jakarta
ENV TZ=Asia/Jakarta
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Install Node.js 20.x via NodeSource (glibc compatible)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && apt-get install -y nodejs && \
    node -v && npm -v

RUN npx playwright@1.52.0 install --with-deps

RUN mkdir -p /var/www/html/dash

# Copy Nginx configuration
COPY nginx.conf /etc/nginx/nginx.conf

# Copy Spring Boot jar and entrypoint script
COPY target/slik-engine-1.1.0.jar app.jar
COPY entrypoint.sh /entrypoint.sh
COPY src/main/resources/application.properties application.properties

RUN chmod +x /entrypoint.sh

EXPOSE 9102
EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
