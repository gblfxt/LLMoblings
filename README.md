# Player2NPC - AI Companions for Minecraft

An AI-powered companion mod for NeoForge 1.21.1 that creates intelligent NPCs you can command using natural language. Companions are powered by Ollama LLM and can perform a wide variety of tasks autonomously.

## Features

### Natural Language Commands
Talk to your companions using the `@` prefix in chat:
- `@Godot follow me` - Companion follows you
- `@Godot get me some wood` - Companion gathers resources
- `@Godot attack that zombie` - Companion fights mobs
- `@Godot go autonomous` - Companion operates independently

### Autonomous Behavior
Companions can operate independently when set to autonomous mode:
- **Hunt** for food when hungry
- **Equip** weapons and armor from inventory
- **Patrol** the area for threats
- **Explore** your base
- **Store** excess items in nearby chests
- **Retrieve** items from AE2 ME networks (if available)

### Personality System
Companions have personalities with:
- Random idle chatter based on situation
- Environmental comments (weather, time of day)
- Combat callouts
- Task completion celebrations
- Emotes with particle effects

### Multi-Player Support
- Other players can talk to companions (configurable)
- Non-owners get friendly chat responses but can't give commands
- Companions remember who their owner is

### Home & Bed Commands
- `setbed` - Find and remember nearby bed location
- `sethome` - Set current location as home
- `home` - Return to home/bed location
- `sleep` - Attempt to sleep in bed at night

### Mod Compatibility
- **AE2/Applied Energistics 2**: Companions can access ME networks for items
- **Various Storage Mods**: Detects chests, barrels, shulker boxes, drawers, metal chests, crates, sophisticated storage

## Commands

### In-Game Commands
```
/companion summon <name>  - Summon a new companion
/companion dismiss <name> - Dismiss a specific companion
/companion dismiss        - Dismiss all your companions
/companion list           - List your companions with status
/companion help           - Show help
```

### Chat Commands (via @prefix)
**Movement:**
- `follow` - Follow the player
- `stay` / `stop` - Stop and stay in place
- `come` - Come to player's location
- `goto <x> <y> <z>` - Go to coordinates

**Actions:**
- `mine <block> <count>` - Mine specific blocks
- `gather <item> <count>` - Gather items
- `attack <target>` - Attack specific mob type
- `defend` - Defend player from hostiles
- `retreat` - Run to player

**Utility:**
- `status` - Report health and inventory
- `scan <radius>` - Scan for mobs/resources
- `autonomous` - Go fully independent
- `explore` - Explore the area

**Home:**
- `setbed` - Remember nearby bed
- `sethome` - Set home at current location
- `home` - Return to home
- `sleep` - Try to sleep

## Configuration

Config file: `config/player2npc-common.toml`

### Ollama Settings
```toml
[ollama]
host = "192.168.70.24"  # Ollama server IP
port = 11434            # Ollama port
model = "llama3:8b"     # Model to use
timeout = 30            # Request timeout (seconds)
```

### Companion Settings
```toml
[companion]
maxPerPlayer = 3              # Max companions per player
takeDamage = true             # Companions can be hurt
needFood = false              # Hunger system (not implemented)
followDistance = 5.0          # Default follow distance
itemPickupRadius = 3          # Item pickup range
loadChunks = true             # Force-load companion's chunk
```

### Chat Settings
```toml
[chat]
prefix = "@"                          # Chat prefix to address companions
broadcastChat = true                  # Broadcast responses to nearby players
allowOtherPlayerInteraction = true    # Let non-owners chat with companions
```

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Ollama server running with a compatible model (llama3:8b recommended)

## Installation

1. Install NeoForge 1.21.1
2. Place `player2npc-2.0.0.jar` in your mods folder
3. Configure Ollama connection in config file
4. Start the server/game

## Troubleshooting

### Companion not responding
- Check Ollama server is running and accessible
- Verify config has correct host/port
- Check server logs for connection errors

### Companion getting stuck
- Companions have stuck detection - they'll give up after ~6-9 seconds
- Try commanding them to `come` to you
- Check logs for pathfinding issues

### Logs
The mod logs to `logs/latest.log` with prefix `[Player2NPC]`:
- INFO level: State changes, commands received, actions taken
- DEBUG level: Detailed pathfinding, inventory operations

## License

MIT License - See LICENSE file for details.

## Credits

Developed for the gblfxt modpack by critic/gblfxt.
