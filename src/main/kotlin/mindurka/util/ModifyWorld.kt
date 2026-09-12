package mindurka.util

import arc.struct.Seq
import arc.struct.StringMap
import arc.util.Log
import mindurka.api.Gamemode
import arc.util.io.Writes
import mindurka.api.Consts
import mindustry.Vars
import mindustry.io.JsonIO
import mindustry.content.Blocks
import mindustry.core.NetServer
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Call
import mindustry.gen.SetFloorCallPacket
import mindustry.gen.SetOverlayCallPacket
import mindustry.gen.SetTileCallPacket
import mindustry.gen.Unit
import mindustry.net.NetConnection
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.environment.Floor

object ModifyWorld {
    /** Mirrors the private NetServer.maxSnapshotSize. */
    private const val maxSnapshotSize = 800

    /** arcnet serializes one object into a 16384-byte buffer (ArcNetProvider: new Server(..., 16384, ...)). */
    private const val rulesPacketLimit = 15_000

    /**
     * Send the ruleset to one connection, or to everyone when [con] is null. Always use this
     * instead of Call.setRules.
     *
     * Rules go as one JSON blob in one packet, and overflowing arcnet's object buffer makes arcnet
     * drop the connection rather than report an error. Only the tags the gamemode declares in
     * [Gamemode.syncedTags] are sent; the rest never leaves the server. Declared tags are packed
     * smallest-first, and anything declared that still does not fit is an error, not a warning --
     * the gamemode said it was needed.
     */
    @JvmStatic
    @JvmOverloads
    fun syncRules(con: NetConnection? = null) {
        // The live ruleset is borrowed and put back rather than Rules.copy()ied: copy() is a JSON
        // write plus a parse of the whole 7-22 kB ruleset ("Not efficient at all, do not use
        // often" -- Rules.java), and Call.setRules serializes on this thread before it returns
        // (Connection.sendTCP -> TcpConnection.send -> serialization.write).
        val rules = Vars.state.rules
        val tags = rules.tags
        rules.tags = StringMap()
        try {
            // TypeIO.writeRules sends JsonIO.write(rules).getBytes(UTF-8), so the budget is bytes,
            // not String.length -- one Cyrillic character costs two of them.
            val bare = utf8Size(JsonIO.write(rules))
            Log.debug("Ruleset @ B without tags, @ tag(s), limit @ B", bare, tags.size, rulesPacketLimit)
            if (bare > rulesPacketLimit) {
                Log.err("Ruleset is @ B even without tags, over the @ B packet limit. Not syncing it: sending it would drop every client.",
                    bare, rulesPacketLimit)
                return
            }

            var budget = rulesPacketLimit - bare
            val wanted = Gamemode.syncedTags
            val entries = ArrayList<Triple<String, String, Int>>(wanted.size)
            // + 8 for quotes, colon and separator
            tags.each { key, value ->
                if (tagWanted(key, wanted)) entries.add(Triple(key, value, utf8Size(key) + utf8Size(value) + 8))
            }
            Log.debug("@ of @ tag(s) declared in Gamemode.syncedTags", entries.size, tags.size)
            entries.sortBy { it.third }

            val dropped = ArrayList<String>()
            for ((key, value, cost) in entries) {
                if (cost <= budget) {
                    budget -= cost
                    rules.tags.put(key, value)
                } else {
                    dropped.add(key)
                }
            }

            if (dropped.isNotEmpty()) {
                Log.err("Ruleset is @ B without tags, leaving @ B; @ declared tag(s) do not fit and are NOT synced: @",
                    bare, rulesPacketLimit - bare, dropped.size, dropped.joinToString(", "))
            }

            if (con == null) Call.setRules(rules) else Call.setRules(con, rules)
        } finally {
            rules.tags = tags
        }
    }

    /** Whether [key] is listed in [patterns], either exactly or by a trailing `*` prefix. */
    private fun tagWanted(key: String, patterns: Seq<String>): Boolean {
        for (i in 0 until patterns.size) {
            val p = patterns.get(i)
            if (p.endsWith("*")) {
                if (key.length >= p.length - 1 && key.regionMatches(0, p, 0, p.length - 1)) return true
            } else if (p == key) return true
        }
        return false
    }

    /** UTF-8 length of a string, without encoding it into a throwaway array. */
    private fun utf8Size(s: String): Int {
        var size = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            size += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return size
    }

    /**
     * Synchronize many buildings to one connection, batched like vanilla's own block snapshot
     * loop. Use this instead of calling [syncBuild] in a loop: blockSnapshot is unreliable, and a
     * packet per building floods the UDP write buffer.
     *
     * Pass the smallest set that answers the question. NetServer.writeBlockSnapshots only ever
     * walks blocks flagged BlockFlag.synced; handing this the whole Groups.build is thousands of
     * buildings and hundreds of packets in one frame.
     */
    @JvmStatic
    fun syncBuilds(con: NetConnection, builds: Iterable<Building>) {
        val prevSyncTarget = NetServer.mdSyncTarget
        NetServer.mdSyncTarget = con.player
        try {
            val writes = Writes(Consts.dataStream)
            Consts.syncStream.reset()
            var sent = 0

            for (build in builds) {
                Consts.dataStream.writeInt(build.pos())
                Consts.dataStream.writeShort(build.block.id.toInt())
                build.writeAll(writes)
                sent++

                if (Consts.syncStream.size() > maxSnapshotSize) {
                    Consts.dataStream.close()
                    Call.blockSnapshot(con, sent.toShort(), Consts.syncStream.toByteArray())
                    sent = 0
                    Consts.syncStream.reset()
                }
            }

            if (sent > 0) {
                Consts.dataStream.close()
                Call.blockSnapshot(con, sent.toShort(), Consts.syncStream.toByteArray())
            }
        } catch (e: Exception) {
            Log.err("Failed to sync buildings", e)
        } finally {
            NetServer.mdSyncTarget = prevSyncTarget
        }
    }

    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun syncBuild(con: NetConnection, build: Building) {
        val prevSyncTarget = NetServer.mdSyncTarget
        NetServer.mdSyncTarget = con.player
        try {
            Consts.syncStream.reset()
            Consts.dataStream.writeInt(build.pos())
            Consts.dataStream.writeShort(build.block.id.toInt())
            build.writeAll(Writes(Consts.dataStream))
            Consts.dataStream.close()
            // toByteArray(), not .bytes: getBytes() returns the buffer, sized by capacity.
            val bytes = Consts.syncStream.toByteArray()
            Call.blockSnapshot(con, 1, bytes)
        } catch (e: Exception) {
            Log.err("Failed to sync building at ${build.tile}", e)
        } finally {
            NetServer.mdSyncTarget = prevSyncTarget
        }
    }
    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun syncBuild(build: Building) {
        Consts.syncStream.reset()
        Consts.dataStream.writeInt(build.pos())
        Consts.dataStream.writeShort(build.block.id.toInt())
        build.writeAll(Writes(Consts.dataStream))
        Consts.dataStream.close()
        val bytes = Consts.syncStream.toByteArray()
        Call.blockSnapshot(1, bytes)
    }

    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netBlock(con: NetConnection, tile: Tile, block: Block, team: Team, rotation: Int) {
        NetServer.mdSyncTarget = con.player
        val packet = SetTileCallPacket();
        packet.tile = tile;
        packet.block = block;
        packet.team = team;
        packet.rotation = rotation;
        con.send(packet, true)
        NetServer.mdSyncTarget = null
    }
    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netBlock(tile: Tile, block: Block, team: Team, rotation: Int) {
        val packet = SetTileCallPacket();
        packet.tile = tile;
        packet.block = block;
        packet.team = team;
        packet.rotation = rotation;
        Vars.net.send(packet, true)
    }

    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netOverlay(con: NetConnection, tile: Tile, overlay: Block) {
        NetServer.mdSyncTarget = con.player
        val packet = SetOverlayCallPacket();
        packet.tile = tile;
        packet.overlay = overlay;
        con.send(packet, true)
        NetServer.mdSyncTarget = null
    }
    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netOverlay(tile: Tile, overlay: Block) {
        val packet = SetOverlayCallPacket();
        packet.tile = tile;
        packet.overlay = overlay;
        Vars.net.send(packet, true);
    }

    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netFloor(con: NetConnection, tile: Tile, floor: Block, overlay: Block) {
        NetServer.mdSyncTarget = con.player
        val packet = SetFloorCallPacket();
        packet.tile = tile;
        packet.floor = floor;
        packet.overlay = overlay;
        con.send(packet, true)
        NetServer.mdSyncTarget = null
    }
    /**
     * Synchronize a building over the network.
     */
    @JvmStatic
    fun netFloor(tile: Tile, floor: Block, overlay: Block) {
        val packet = SetFloorCallPacket();
        packet.tile = tile;
        packet.floor = floor;
        packet.overlay = overlay;
        Vars.net.send(packet, true);
    }

    /**
     * Placement check that actually checks placement.
     */
    @JvmStatic
    fun canPlaceOn(block: Block, tile: Tile?, team: Team, rotation: Int): Boolean {
        val tile = tile ?: return false

        if (!block.canPlaceOn(tile, team, rotation)) return false
        if (block.isFloor) return true

        block.eachBlockOffset(tile) { x, y ->
            val tile = Vars.world.tile(x, y) ?: return@eachBlockOffset
            if (!tile.floor().hasSurface()) return false
            // TODO: Replace blocks that Mindustry allows replacing.
            if (tile.block() != Blocks.air && !tile.block().alwaysReplace) return false
        }

        return true
    }

    /** Properly teleport a unit. */
    @JvmStatic
    fun teleport(unit: Unit, x: Float, y: Float) {
        val player = unit.player ?: run {
            unit.set(x, y)
            return
        }
        player.clearUnit()
        unit.set(x, y)
        Call.setPosition(player.con, x, y)
        Call.setCameraPosition(player.con, x, y)
        player.unit(unit)
    }
}

fun Block.canActuallyPlaceOn(tile: Tile?, team: Team, rotation: Int): Boolean = ModifyWorld.canPlaceOn(this, tile, team, rotation)
