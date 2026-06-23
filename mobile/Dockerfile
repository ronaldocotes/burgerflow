# Build stage
FROM node:20-alpine AS builder

# Set working directory
WORKDIR /app

# Install Android build tools (for building APK/AAB)
RUN apk add --no-cache \
    openjdk17-jre \
    android-sdk \
    gradle \
    && update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-17-openjdk/bin/java 100 \
    && update-alternatives --install /usr/bin/javac javac /usr/lib/jvm/java-17-openjdk/bin/javac 100

# Install dependencies for React Native
RUN npm install -g react-native-cli

# Copy package files
COPY package.json .

# Install npm dependencies
RUN npm install --frozen-lockfile

# Copy all files
COPY . .

# Build Android app (for production)
# RUN cd android && ./gradlew assembleRelease

# Runtime stage
FROM node:20-alpine AS runtime

# Set working directory
WORKDIR /app

# Copy from builder
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/package-lock.json ./package-lock.json
COPY --from=builder /app/. .

# Install only production dependencies
RUN npm install --production

# Create non-root user
RUN addgroup -S mobileuser && adduser -S mobileuser -G mobileuser

# Change ownership
RUN chown -R mobileuser:mobileuser /app

# Switch to non-root user
USER mobileuser

# Expose ports
EXPOSE 8081  # Metro bundler
EXPOSE 19000 # Debugger
EXPOSE 19001 # Debugger
EXPOSE 19002 # Debugger

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD node -e "require('http').get('http://localhost:8081/status', (res) => { process.exit(res.statusCode === 200 ? 0 : 1); });" || exit 1

# Run Metro bundler
CMD ["npx", "react-native", "start", "--port", "8081"]
