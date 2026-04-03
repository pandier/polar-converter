package io.github.pandier.polarconverter;

import net.hollowcube.polar.ChunkSelector;
import net.hollowcube.polar.PolarLoader;
import net.hollowcube.polar.PolarWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Taken from hollow-cube/polar on GitHub and modified.
 */
public class AnvilPolar {

    /**
     * Convert the anvil world at the given path to a Polar world. The provided world height range
     * will be used to determine which sections will be included in the Polar world. If a section is missing,
     * an empty polar section will be included in its place.
     * <br />
     * Only the selected chunks will be included in the resulting Polar world.
     *
     * @param regionPath    Path to the region directory inside the anvil world directory
     * @param dimensionType The dimension type for the regions.
     * @param selector      Chunk selector to use to determine which chunks to include in the Polar world
     * @return The Polar world representing the given Anvil world
     * @throws IOException If there was an error reading the anvil world
     */
    public static @NotNull PolarWorld anvilToPolar(@NotNull Path regionPath, RegistryKey<DimensionType> dimensionType, @NotNull ChunkSelector selector) throws IOException {
        final Instance instance = new InstanceContainer(UUID.randomUUID(), dimensionType); // Empty instance, never registered

        @SuppressWarnings("UnstableApiUsage")
        DimensionType cachedDimensionType = instance.getCachedDimensionType();
        byte minSection = (byte) (cachedDimensionType.minY() / Chunk.CHUNK_SECTION_SIZE);
        byte maxSection = (byte) ((cachedDimensionType.minY() + cachedDimensionType.height()) / Chunk.CHUNK_SECTION_SIZE - 1);

        var world = new PolarWorld(
                PolarWorld.LATEST_VERSION,
                MinecraftServer.DATA_VERSION,
                PolarWorld.DEFAULT_COMPRESSION,
                minSection, maxSection,
                new byte[0],
                List.of()
        );

        var polarLoader = new PolarLoader(world);
        var anvilReader = new AnvilReader(regionPath);

        var chunks = new ArrayList<Chunk>(1024);

        try (var files = Files.walk(regionPath, 1)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                var regionFile = iterator.next();

                if (!regionFile.getFileName().toString().endsWith(".mca")) continue;

                var nameParts = regionFile.getFileName().toString().split("\\.");
                var regionX = Integer.parseInt(nameParts[1]);
                var regionZ = Integer.parseInt(nameParts[2]);

                readAnvilChunks(chunks, instance, anvilReader, regionX, regionZ, selector);

                polarLoader.saveChunks(chunks);

                chunks.clear();
            }
        }

        return world;
    }

    private static void readAnvilChunks(@NotNull List<Chunk> output, @NotNull Instance instance, @NotNull AnvilReader anvilReader, int regionX, int regionZ, @NotNull ChunkSelector selector) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                int chunkX = x + (regionX * 32);
                int chunkZ = z + (regionZ * 32);

                if (!selector.test(chunkX, chunkZ)) continue;

                var chunk = anvilReader.loadChunk(instance, chunkX, chunkZ);
                if (chunk == null) continue;

                output.add(chunk);
            }
        }
    }
}
