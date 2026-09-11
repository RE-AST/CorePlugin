package mindurka.util

import arc.util.Log
import arc.util.io.Writes
import mindurka.api.Consts
import mindustry.Vars
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

    /**
     * Synchronize many buildings to one connection.
     *
     * Use this instead of calling [syncBuild] in a loop. `blockSnapshot` is an unreliable packet,
     * so a packet per building goes straight into the server's 41 000-byte UDP write buffer with
     * no backpressure; on overflow `arc.net.Server.sendToAllUDP` closes the connection itself
     * (`con.close(DcReason.error)`), which the server only notices later as "disappeared".
     * Batching to [maxSnapshotSize] is what vanilla's own block snapshot loop does.
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
                    Consts.dataStream.flush()
                    Call.blockSnapshot(con, sent.toShort(), Consts.syncStream.toByteArray())
                    sent = 0
                    Consts.syncStream.reset()
                }
            }

            if (sent > 0) {
                Consts.dataStream.flush()
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
            // toByteArray(), not .bytes: getBytes() hands back the whole ReusableByteOutStream
            // buffer, whose length is its capacity, so the packet carried hundreds of stale bytes
            // per building instead of the ~30 actually written.
            val bytes = Consts.syncStream.toByteArray()
            Call.blockSnapshot(con, 1, bytes)
        } catch (_: Exception) {} finally {
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
        // See the note in the overload above.
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
