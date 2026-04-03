package io.github.pandier.polarconverter;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.kyori.adventure.nbt.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static net.minestom.server.coordinate.CoordConversion.*;

/**
 * Taken from Minestom (AnvilLoader) and modified.
 */
public class AnvilReader {
    private final static Logger LOGGER = LoggerFactory.getLogger(AnvilReader.class);
    private static final DynamicRegistry<Biome> BIOME_REGISTRY = MinecraftServer.getBiomeRegistry();
    private final static int PLAINS_ID = BIOME_REGISTRY.getId(Biome.PLAINS);
    private static final CompoundBinaryTag[] BLOCK_STATE_ID_2_OBJECT_CACHE = new CompoundBinaryTag[Block.statesCount()];

    private final Map<String, RegionFile> alreadyLoaded = new ConcurrentHashMap<>();
    private final Path regionPath;

    /**
     * Represents the chunks currently loaded per region. Used to determine when a region file can be unloaded.
     * <p>
     * RegionIndex = Set<ChunkIndex>
     */
    private final Long2ObjectOpenHashMap<LongSet> perRegionLoadedChunks = new Long2ObjectOpenHashMap<>();
    private final ReentrantLock perRegionLoadedChunksLock = new ReentrantLock();

    public AnvilReader(Path regionPath) {
        this.regionPath = regionPath;
    }

    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        if (!Files.exists(regionPath)) {
            // No world folder
            return null;
        }
        try {
            return loadMCA(instance, chunkX, chunkZ);
        } catch (Exception e) {
            MinecraftServer.getExceptionManager().handleException(e);
            return null;
        }
    }

    private @Nullable Chunk loadMCA(Instance instance, int chunkX, int chunkZ) throws IOException {
        final RegionFile mcaFile = getMCAFile(chunkX, chunkZ);
        if (mcaFile == null) return null;
        final CompoundBinaryTag chunkData = mcaFile.readChunkData(chunkX, chunkZ);
        if (chunkData == null) return null;

        // Load the chunk data (assuming it is fully generated)
        final Chunk chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
        synchronized (chunk) { // todo: boo, synchronized
            final String status = chunkData.getString("status");
            // TODO: Should we handle other statuses?
            if (status.isEmpty() || "minecraft:full".equals(status)) {
                // Blocks + Biomes
                loadSections(chunk, chunkData);
                // Block entities
                loadBlockEntities(chunk, chunkData);
                chunk.loadHeightmapsFromNBT(chunkData.getCompound("Heightmaps"));
            } else {
                LOGGER.warn("Skipping partially generated chunk at {}, {} with status {}", chunkX, chunkZ, status);
            }
            CompoundBinaryTag handlerData = CompoundBinaryTag.builder()
                    .put(chunkData)
                    .remove("Heightmaps")
                    .remove("sections")
                    .remove("sections")
                    .remove("block_entities")
                    .build();
            chunk.tagHandler().updateContent(handlerData);
        }

        // Cache the index of the loaded chunk
        perRegionLoadedChunksLock.lock();
        try {
            final int regionX = chunkToRegion(chunkX), regionZ = chunkToRegion(chunkZ);
            final long regionIndex = regionIndex(regionX, regionZ);
            var chunks = perRegionLoadedChunks.computeIfAbsent(regionIndex, r -> new LongOpenHashSet()); // region cache may have been removed on another thread due to unloadChunk
            final long chunkIndex = chunkIndex(chunkX, chunkZ);
            chunks.add(chunkIndex);
        } finally {
            perRegionLoadedChunksLock.unlock();
        }
        return chunk;
    }

    private @Nullable RegionFile getMCAFile(int chunkX, int chunkZ) {
        final int regionX = chunkToRegion(chunkX), regionZ = chunkToRegion(chunkZ);
        final String fileName = RegionFile.getFileName(regionX, regionZ);

        final RegionFile loadedFile = alreadyLoaded.get(fileName);
        if (loadedFile != null) return loadedFile;

        perRegionLoadedChunksLock.lock();
        try {
            return alreadyLoaded.computeIfAbsent(fileName, n -> {
                final Path regionPath = this.regionPath.resolve(n);
                if (!Files.exists(regionPath)) {
                    return null;
                }

                try {
                    final long regionIndex = regionIndex(regionX, regionZ);
                    LongSet previousVersion = perRegionLoadedChunks.put(regionIndex, new LongOpenHashSet());
                    assert previousVersion == null : "The AnvilLoader cache should not already have data for this region.";
                    return new RegionFile(regionPath);
                } catch (IOException e) {
                    MinecraftServer.getExceptionManager().handleException(e);
                    return null;
                }
            });
        } finally {
            perRegionLoadedChunksLock.unlock();
        }
    }

    private void loadSections(Chunk chunk, CompoundBinaryTag chunkData) {
        for (BinaryTag sectionTag : chunkData.getList("sections", BinaryTagTypes.COMPOUND)) {
            if (!(sectionTag instanceof CompoundBinaryTag sectionData)) {
                LOGGER.warn("Invalid section tag in chunk data: {}", sectionTag);
                continue;
            }

            final int sectionY = sectionData.getInt("Y", Integer.MIN_VALUE);
            Check.stateCondition(sectionY == Integer.MIN_VALUE, "Missing section Y value");
            if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) {
                // Vanilla stores a section below and above the world for lighting, throw it out.
                continue;
            }

            final Section section = chunk.getSection(sectionY);

            // Lighting
            if (sectionData.get("SkyLight") instanceof ByteArrayBinaryTag skyLightTag && skyLightTag.size() == 2048) {
                section.skyLight().set(skyLightTag.value());
            }
            if (sectionData.get("BlockLight") instanceof ByteArrayBinaryTag blockLightTag && blockLightTag.size() == 2048) {
                section.blockLight().set(blockLightTag.value());
            }

            {   // Biomes
                final CompoundBinaryTag biomesTag = sectionData.getCompound("biomes");
                final ListBinaryTag biomePaletteTag = biomesTag.getList("palette", BinaryTagTypes.STRING);
                int[] convertedBiomePalette = loadBiomePalette(biomePaletteTag);
                if (convertedBiomePalette.length == 1) {
                    // One solid block, no need to check the data
                    section.biomePalette().fill(convertedBiomePalette[0]);
                } else if (convertedBiomePalette.length > 1) {
                    final long[] packedIndices = biomesTag.getLongArray("data");
                    Check.stateCondition(packedIndices.length == 0, "Missing packed biomes data");
                    section.biomePalette().load(convertedBiomePalette, packedIndices);
                }
            }

            {   // Blocks
                final CompoundBinaryTag blockStatesTag = sectionData.getCompound("block_states");
                final ListBinaryTag blockPaletteTag = blockStatesTag.getList("palette", BinaryTagTypes.COMPOUND);
                final int[] convertedPalette = loadBlockPalette(blockPaletteTag);
                if (blockPaletteTag.size() == 1) {
                    // One solid block, no need to check the data
                    section.blockPalette().fill(convertedPalette[0]);
                } else if (blockPaletteTag.size() > 1) {
                    final long[] packedStates = blockStatesTag.getLongArray("data");
                    Check.stateCondition(packedStates.length == 0, "Missing packed states data");
                    section.blockPalette().load(convertedPalette, packedStates);
                }
            }
        }
    }

    private int[] loadBlockPalette(ListBinaryTag paletteTag) {
        final int length = paletteTag.size();
        int[] convertedPalette = new int[length];
        for (int i = 0; i < length; i++) {
            CompoundBinaryTag paletteEntry = paletteTag.getCompound(i);
            final String blockName = paletteEntry.getString("Name");
            if (blockName.equals("minecraft:air")) {
                convertedPalette[i] = Block.AIR.stateId();
            } else {
                Block block = Objects.requireNonNull(Block.fromKey(blockName), "Unknown block " + blockName);
                // Properties
                final CompoundBinaryTag propertiesNBT = paletteEntry.getCompound("Properties");
                if (!propertiesNBT.isEmpty()) {
                    final Map<String, String> properties = HashMap.newHashMap(propertiesNBT.size());
                    for (var property : propertiesNBT) {
                        if (property.getValue() instanceof StringBinaryTag propertyValue) {
                            properties.put(property.getKey(), propertyValue.value());
                        } else {
                            try {
                                LOGGER.warn("Fail to parse block state properties {}, expected a string tag for {}, but contents were {}",
                                        propertiesNBT, property.getKey(), MinestomAdventure.tagStringIO().asString(property.getValue()));
                            } catch (IOException e) {
                                LOGGER.warn("Fail to parse block state properties {}, expected a string tag for {}, but contents were a {} tag", propertiesNBT, property.getKey(), property.getValue().examinableName());
                            }
                        }
                    }
                    block = block.withProperties(properties);
                }

                convertedPalette[i] = block.stateId();
            }
        }
        return convertedPalette;
    }

    private int[] loadBiomePalette(ListBinaryTag paletteTag) {
        final int length = paletteTag.size();
        int[] convertedPalette = new int[length];
        for (int i = 0; i < length; i++) {
            final String name = paletteTag.getString(i);
            int biomeId = BIOME_REGISTRY.getId(RegistryKey.unsafeOf(name));
            if (biomeId == -1) biomeId = PLAINS_ID;
            convertedPalette[i] = biomeId;
        }
        return convertedPalette;
    }

    private void loadBlockEntities(Chunk loadedChunk, CompoundBinaryTag chunkData) {
        for (BinaryTag blockEntityTag : chunkData.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            if (!(blockEntityTag instanceof CompoundBinaryTag blockEntity)) {
                LOGGER.warn("Invalid block entity tag in chunk data: {}", blockEntityTag);
                continue;
            }
            final int x = blockEntity.getInt("x"), y = blockEntity.getInt("y"), z = blockEntity.getInt("z");
            final int localX = globalToSectionRelative(x), localY = globalToSectionRelative(y), localZ = globalToSectionRelative(z);
            Section section = loadedChunk.getSectionAt(y);
            final int stateId = section.blockPalette().get(localX, localY, localZ);
            Block block = Block.fromStateId(stateId);
            assert block != null;
            // Load the block handler if the id is present
            if (blockEntity.get("id") instanceof StringBinaryTag blockEntityId) {
                final BlockHandler handler = MinecraftServer.getBlockManager().getHandlerOrDummy(blockEntityId.value());
                block = block.withHandler(handler);
            }
            // Remove anvil tags
            CompoundBinaryTag trimmedTag = CompoundBinaryTag.builder()
                    .put(blockEntity)
                    .remove("id").remove("keepPacked")
                    .remove("x").remove("y").remove("z")
                    .build();

            // Place block
            final Block finalBlock = !trimmedTag.isEmpty() ? block.withNbt(trimmedTag) : block;
            loadedChunk.setBlock(x, y, z, finalBlock);
        }
    }
}
