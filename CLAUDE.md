# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean and rebuild
./gradlew clean build

# Install debug APK on connected device/emulator
./gradlew installDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Architecture Overview

This is an Android VPN client that establishes encrypted tunnels to a VPN server using a custom BSON-based protocol over TLS 1.3.

### Component Flow

```
MainActivity (UI) ←→ MyVpnService (Foreground Service) ←→ VpnClientWrapper ←→ VpnClient (Protocol)
                                                              ↓
                                                         TunAndroid (TUN Interface)
```

### Core Components

**VpnClient** (`su.grinev.myvpn.VpnClient`) - Protocol implementation with state machine:
- States: DISCONNECTED → CONNECTING → LOGIN → AWAITING_LOGIN_RESPONSE → LIVE
- Commands: LOGIN (1000), FORWARD_PACKET (1020), DISCONNECT (1040), PING (1060), PONG (1080)
- Uses BSON serialization via JBson library for all protocol messages

**MyVpnService** (`su.grinev.myvpn.MyVpnService`) - Android VpnService wrapper that manages service lifecycle and broadcasts state changes via LocalBroadcastManager.

**VpnClientWrapper** (`su.grinev.myvpn.VpnClientWrapper`) - Bridges VpnClient with the TUN interface, forwarding packets between the device and VPN server.

**TunAndroid** (`su.grinev.myvpn.TunAndroid`) - Implements Android's VPN.Builder API to create and manage the TUN interface with configurable virtual IP and routing.

**BufferPool** (`su.grinev.myvpn.BufferPool`) - Thread-safe object pool for byte arrays to minimize GC overhead during packet processing.

**DebugLog** (`su.grinev.myvpn.DebugLog`) - Centralized logging with observer pattern, maintains 32-64KB circular buffer and broadcasts to UI.

### Model DTOs (su.grinev.model)

BSON-serialized objects using `@BsonType` discriminator for polymorphism:
- `VpnLoginRequestDto` - Login request with JWT token
- `VpnIpResponseDto` - Server response with assigned virtual IP
- `VpnForwardPacketRequestDto` - Encapsulated IP packets
- `RequestDto`, `ResponseDto`, `Packet` - Protocol wrappers

## Project Configuration

- **Target SDK**: 34 (Android 14)
- **Min SDK**: 34
- **Java version**: 21
- **Gradle version**: 8.13
- **ViewBinding**: Enabled

## Key Dependencies

- **JBson** (com.github.GR-ROM:JBson) - BSON serialization for protocol messages
- **Lombok** - Code generation for boilerplate reduction
- **AndroidX Navigation** - Fragment navigation
