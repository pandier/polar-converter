package io.github.pandier.polarconverter

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.MutuallyExclusiveGroupException
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.hollowcube.polar.AnvilPolar
import net.hollowcube.polar.ChunkSelector
import net.hollowcube.polar.PolarWorld
import net.hollowcube.polar.PolarWriter
import net.minestom.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

fun main(args: Array<String>) {
    PolarConverter().main(args)
}

class PolarConverter : CliktCommand() {
    val input: Path by argument("input", help = "Path to the Anvil world")
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)

    val output: Path by argument("output", help = "Path to the output Polar file")
        .path()

    val compression: PolarWorld.CompressionType by option("-c", "--compression", help = "Compression type")
        .enum<PolarWorld.CompressionType> { it.name.lowercase() }
        .default(PolarWorld.CompressionType.ZSTD)

    val fromTo: FromToOptions? by FromToOptions().cooccurring()

    val radius: RadiusOptions? by RadiusOptions().cooccurring()

    override fun run() {
        val chunkSelector = mutuallyExclusive(
            "--radius" to radius,
            "--from --to" to fromTo,
        )?.toChunkSelector() ?: ChunkSelector.all()

        MinecraftServer.init()

        val polarWorld = AnvilPolar.anvilToPolar(input, chunkSelector)
        polarWorld.setCompression(compression)

        val bytes = PolarWriter.write(polarWorld)

        Files.write(output, bytes)
    }
}

sealed class ChunkSelectionOptions : OptionGroup() {
    abstract fun toChunkSelector(): ChunkSelector
}

class FromToOptions : ChunkSelectionOptions() {
    val from: Pair<Int, Int> by option("--from", help = "The minimum inclusive X and Z chunk coordinates selected for conversion")
        .int()
        .pair()
        .required()

    val to: Pair<Int, Int> by option("--to", help = "The maximum inclusive X and Z chunk coordinates selected for conversion")
        .int()
        .pair()
        .required()

    override fun toChunkSelector(): ChunkSelector {
        val minX = minOf(from.first, to.first)
        val minZ = minOf(from.second, to.second)
        val maxX = maxOf(from.first, to.first)
        val maxZ = maxOf(from.second, to.second)
        return { x, z -> x >= minX && z >= minZ && x <= maxX && z <= maxZ }
    }
}

class RadiusOptions : ChunkSelectionOptions() {
    val radius: Int by option("--radius", help = "Radius of chunks selected for conversion")
        .int()
        .required()

    val center: Pair<Int, Int>? by option( "--center", help = "The center X and Z chunk coordinates for radius")
        .int()
        .pair()

    override fun toChunkSelector(): ChunkSelector {
        return center?.let { center -> ChunkSelector.radius(center.first, center.second, radius) }
            ?: ChunkSelector.radius(radius)
    }
}

private fun <T : OptionGroup> mutuallyExclusive(vararg groups: Pair<String, T?>): T? {
    var selected: T? = null
    for ((_, group) in groups) {
        if (group == null)
            continue
        if (selected != null)
            throw MutuallyExclusiveGroupException(Arrays.stream(groups).map { it.first }.toList())
        selected = group
    }
    return selected
}
