<a href> <img src="https://raw.githubusercontent.com/omdumrotat/flicexyzantixray/master/minecraft_title.png"> </a>
# flicexyzantixray
- ok, the name seems MISLEADING when its hiding block at a y level not through fancy raytracing or pure ifyoucantseeitthenfakeoresforyou techniques (im looking at you orebfuscator and paper antixray engine mode 2 and 3). At best, this is only an anti base esp plugin.
- Hides blocks from Y ≤ 16 if the player is ≥ 31.0; very useful against freecam hacks (for real xray prevention you should check out raytraceantixray and use it alongside this)
# REQUIRES paper 1.20.6+ and packetevents 2.8.0
# permission: 
- ylevelhider.admin: /ylevelhiderworld, /ylevelhiderreload
- ylevelhider.debug: /ylevelhiderdebug
# known issues:
- some chunks (around 4-8) might not be visible to the player when they're constantly moving up and down while also loading new map sections. This bug can be temporarily resolved by doing basically anything that revolves around refreshing chunks (think of /home, /tp, moving up and down, rejoining...) Any PR that fixes this issue will be greatly appreciated. (fixed in `test` branch)
# compiling
clone the repo, import to IDLE or your IDE of your choice and build with Maven
