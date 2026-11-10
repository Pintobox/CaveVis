package me.pintobox.pintoAntiCheat.cavevis.delivery;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CaveVisColumnPackets {
    private final Map<UUID, LevelChunkSection> airTemplates =
        new HashMap<>();

    public void clear() {
        airTemplates.clear();
    }

    public ClientboundLevelChunkWithLightPacket create(
        World world,
        int x,
        int z,
        BitSet visible
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Packet capture needs the server thread"
            );
        }

        if (!world.isChunkLoaded(x, z)) return null;

        LevelChunk source = (LevelChunk) ((CraftChunk)
            world.getChunkAt(x, z)).getHandle(ChunkStatus.FULL);

        ServerLevel level = (ServerLevel) source.getLevel();
        LevelChunk view = new LevelChunk(level, source.getPos());

        LevelChunkSection[] src = source.getSections();
        LevelChunkSection[] dst = view.getSections();

        LevelChunkSection air = airTemplates.get(world.getUID());

        for (int i = 0; i < src.length; i++) {
            if (visible.get(i)) {
                dst[i] = src[i];
                continue;
            }

            if (air == null) {
                air = new LevelChunkSection(level.palettedContainerFactory());
                airTemplates.put(world.getUID(), air);
            }

            // Use an empty block palette and keep the original biomes.
            dst[i] = new LevelChunkSection(
                air.getStates(),
                src[i].getBiomes().copy()
            );
        }

        for (var entry : source.getHeightmaps()) {
            view.setHeightmap(
                entry.getKey(),
                entry.getValue().getRawData()
            );
        }

        int bottom = Math.floorDiv(world.getMinHeight(), 16);

        // Hidden sections must not include chest/sign/etc. data
        // inside the full column packet.
        for (var entry : source.getBlockEntities().entrySet()) {
            int section =
                (entry.getKey().getY() >> 4) - bottom;

            if (section >= 0 && visible.get(section)) {
                view.getBlockEntities().put(
                    entry.getKey(),
                    entry.getValue()
                );
            }
        }

        return new ClientboundLevelChunkWithLightPacket(
            view,
            level.getChunkSource().getLightEngine(),
            null,
            null
        );
    }
}