package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The dummy world every Mob Editor preview mob is created in. It is a real ClientLevel (so mods that cast or inspect the level
 * are happy) but it is never ticked, never rendered, has its own dimension key, its own time / weather / sky / scoreboard
 * data and its own entity storage, and everything that could reach the outside is swallowed: sounds, particles, level events.
 * A mob that spawns something spawns it into this world, where nothing ever looks at it again.
 * <p>
 * Two worlds exist: a dry one and a flooded one (every position is water) for modded swimmers, so their own "am I in water"
 * code sees water instead of a flag forced from outside.
 * <p>
 * Sounds that still get out (a mod calling the SoundManager directly) are stopped by PreviewZone.guarded + PreviewSilencer;
 * a shader a mod turns on is undone by PreviewShaderGuard.
 */
public final class PreviewLevel extends ClientLevel {
    private static final ResourceKey<Level> AIR_KEY =
            ResourceKey.create(Registries.DIMENSION, EncephalonMod.id("preview_air"));
    private static final ResourceKey<Level> WATER_KEY =
            ResourceKey.create(Registries.DIMENSION, EncephalonMod.id("preview_water"));

    private static PreviewLevel air;
    private static PreviewLevel water;
    private static ClientPacketListener owner;

    private final boolean flooded;

    private PreviewLevel(ClientPacketListener connection, ResourceKey<Level> key, boolean flooded) {
        super(connection, new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false), key,
                connection.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD),
                2, 2, () -> InactiveProfiler.INSTANCE, Minecraft.getInstance().levelRenderer, false, 0L);
        this.flooded = flooded;
    }

    /** The dummy world (dry or flooded), made on first use; null if it could not be built (callers fall back to the real level). */
    public static PreviewLevel get(boolean flooded) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return null;
        }
        if (owner != connection) {
            air = null;
            water = null;
            owner = connection;
        }
        try {
            if (flooded) {
                if (water == null) {
                    water = new PreviewLevel(connection, WATER_KEY, true);
                }
                return water;
            }
            if (air == null) {
                air = new PreviewLevel(connection, AIR_KEY, false);
            }
            return air;
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: could not build the dummy world, using the real level", ex);
            return null;
        }
    }

    /** Editor closed: drop the worlds (and with them every entity a preview mob made in them). */
    public static void closeAll() {
        air = null;
        water = null;
        owner = null;
    }

    // ---- terrain: nothing, or water everywhere

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return flooded ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return flooded ? Fluids.WATER.defaultFluidState() : Fluids.EMPTY.defaultFluidState();
    }

    // Entities refuse to do their fluid logic when their box touches an unloaded chunk, and this world has no chunks.
    // These are deliberately written without @Override: any variant that does not exist in this version is just an overload.
    public boolean hasChunk(int chunkX, int chunkZ) {
        return true;
    }

    public boolean hasChunkAt(BlockPos pos) {
        return true;
    }

    public boolean hasChunkAt(int x, int z) {
        return true;
    }

    public boolean hasChunksAt(int fromX, int fromZ, int toX, int toZ) {
        return true;
    }

    public boolean hasChunksAt(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return true;
    }

    public boolean hasChunksAt(BlockPos from, BlockPos to) {
        return true;
    }

    // ---- everything that could leave this world is swallowed (no @Override, see above)

    public void playSeededSound(Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
    }

    public void playSeededSound(Player except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
    }

    public void playLocalSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch,
                               boolean distanceDelay) {
    }

    public void playLocalSound(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    public void addParticle(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs) {
    }

    public void addParticle(ParticleOptions options, boolean force, double x, double y, double z, double xs, double ys, double zs) {
    }

    public void addAlwaysVisibleParticle(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs) {
    }

    public void addAlwaysVisibleParticle(ParticleOptions options, boolean ignoreRange, double x, double y, double z,
                                         double xs, double ys, double zs) {
    }

    public void levelEvent(Player except, int type, BlockPos pos, int data) {
    }

    public void globalLevelEvent(int type, BlockPos pos, int data) {
    }
}
