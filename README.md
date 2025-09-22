<a href> <img src="https://raw.githubusercontent.com/omdumrotat/flicexyzantixray/master/minecraft_title.png"> </a>
# flicexyzantixray

[![CI](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/ci.yml/badge.svg)](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/ci.yml)
[![Security Scan](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/security.yml/badge.svg)](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/security.yml)
[![Code Quality](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/code-quality.yml/badge.svg)](https://github.com/omdumrotat/flicexyzantixray/actions/workflows/code-quality.yml)
- ~~ok, the name seems MISLEADING when its hiding block at a y level not through fancy raytracing or pure ifyoucantseeitthenfakeoresforyou techniques (im looking at you orebfuscator and paper antixray engine mode 2 and 3). At best, this is only an anti base esp plugin.~~
- **NEW**: Now includes a 3x3 view box feature with ray-tracing! When players go underground (Y≤30), they get a limited 3x3 view box around them with fake deepslate covering everything else. Looking at the fake deepslate uncovers the area dynamically!
- ~~Hides blocks from Y ≤ 16 if the player is ≥ 31.0; very useful against freecam hacks~~
- **UPDATED**: Hides blocks from Y ≤ 16 when the player is underground (≤ 30 Y); very useful against freecam hacks and X-ray
- **NEW**: Also hides entities (armor stands, item frames, etc.) to prevent ESP exploits
- For comprehensive X-ray prevention, use alongside raytraceantixray

## New Features in v2.5
- **3x3 View Box**: Configurable view box (3x3, 5x5, etc.) around player when underground
- **Dynamic Uncovering**: Looking at fake deepslate blocks reveals the real terrain behind them  
- **Entity Hiding**: Prevents ESP by hiding entities in obscured areas
- **Configurable Y Threshold**: Set the Y level where the effect activates (default: Y≤30)
- **Performance Optimized**: Uses efficient packet interception with caching
# REQUIRES paper 1.20.6+ and packetevents 2.8.0
# **NOW SUPPORTS FOLIA** - Compatible with both Paper and Folia servers
- Works with Folia's regionized multithreading architecture
- Automatically detects server type and uses appropriate schedulers
- No configuration changes needed for Folia compatibility
# permission: 
- ylevelhider.admin: /ylevelhiderworld, /ylevelhiderreload
- ylevelhider.debug: /ylevelhiderdebug
# known issues:
- none (so far)
# compiling
clone the repo, import to IDLE or your IDE of your choice and build with Maven
