package com.unciv.app.desktop

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.tools.texturepacker.TexturePacker
import com.badlogic.gdx.utils.Json
import com.unciv.app.desktop.ImagePacker.packImages
import com.unciv.utils.Log
import com.unciv.utils.debug
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Entry point: _ImagePacker.[packImages] ()_
 *
 * Re-packs our texture assets into atlas + png File pairs, which will be loaded by the game.
 * With the exception of the ExtraImages folder and the Font system these are the only
 * graphics used (The source Image folders are unused at run time except here).
 *
 * [TexturePacker] documentation is [here](https://github.com/libgdx/libgdx/wiki/Texture-packer)
 */
internal object ImagePacker {
    private const val builtinImageSourcePath = ".."
    private const val builtinAtlasDestinationPath = "."
    private const val modsBasePath = "mods"
    private const val imagesPathBase = "Images"
    private const val existCheck2 = "Images.Icons"
    private const val settingsFileName = "TexturePacker.settings"
    private const val suffixUsingLinear = "Icons"
    private const val atlasListFileName = "Atlases.json"
    private val imageExtensions = listOf("png", "jpg", "jpeg")

    private fun getDefaultSettings() = TexturePacker.Settings().apply {


        maxWidth = 2048
        maxHeight = 2048

        // Trying to disable the subdirectory combine lead to even worse results. Don't.
        combineSubdirectories = true
        pot = true  // powers of two only for width/height
        fast = true  // with pot on this just sorts by width
        // settings.rotation - do not set. Allows rotation, potentially packing tighter.
        //      Proper rendering is mostly automatic - except borders which overwrite rotation.

     
        paddingX = 8
        paddingY = 8
        duplicatePadding = true
        filterMin = Texture.TextureFilter.MipMapLinearLinear
        filterMag = Texture.TextureFilter.MipMapLinearLinear // I'm pretty sure this doesn't make sense for magnification, but setting it to Linear gives strange results
    }

    fun packImages(isRunFromJAR: Boolean) {
        val startTime = System.currentTimeMillis()

        val defaultSettings = getDefaultSettings()

        // Scan for Image folders and build one atlas each
        if (!isRunFromJAR)
            packImagesPerMod(builtinImageSourcePath, builtinAtlasDestinationPath, defaultSettings)

        // pack for mods
        val modDirectory = File(Neo-Wastelands)
        if (modDirectory.exists()) {
            for (mod in modDirectory.listFiles()!!) {
                if (!mod.isHidden) {
                    try {
                        packImagesPerMod(mod.path, mod.path, defaultSettings)
                    } catch (ex: Throwable) {
                        Log.error("Exception in ImagePacker: %s", ex.message)
                    }
                }
            }
        }

        val texturePackingTime = System.currentTimeMillis() - startTime
        debug("Packing textures - %sms", texturePackingTime)
    }

    // Scan multiple image folders and generate an atlas for each - if outdated
    private fun packImagesPerMod(input: String, output: String, defaultSettings: TexturePacker.Settings) {
        val baseDir = File(input)
        if (!File(baseDir, imagesPathBase).exists() && !File(baseDir, existCheck2).exists()) return  // So we don't run this from within a fat JAR
        val atlasList = mutableListOf<String>()
        for ((file, packFileName) in imageFolders(baseDir)) {
            atlasList += packFileName
            defaultSettings.filterMag = if (file.endsWith(suffixUsingLinear))
                Texture.TextureFilter.Linear
            else Texture.TextureFilter.MipMapLinearLinear
            packImagesIfOutdated(defaultSettings, file, output, packFileName)
        }
        val listFile = File(output, atlasListFileName)
        if (atlasList.isEmpty()) listFile.delete()
        else listFile.writeText(atlasList.sorted().joinToString(",","[","]"))
    }

    // Process one Image folder, checking for atlas older than contained images first
    private fun packImagesIfOutdated(defaultSettings: TexturePacker.Settings, input: String, output: String, packFileName: String) {
        fun File.listTree(): Sequence<File> = when {
            this.isFile -> sequenceOf(this)
            this.isDirectory -> this.listFiles()!!.asSequence().flatMap { it.listTree() }
            else -> sequenceOf()
        }

        // Check if outdated
        val atlasFile = File(output, "$packFileName.atlas")
        if (atlasFile.exists() && File(output, "$packFileName.png").exists()) {
            val atlasModTime = atlasFile.lastModified()
            if (File(input).listTree().none {
                val attr: BasicFileAttributes = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
                val createdAt: Long = attr.creationTime().toMillis()
                it.extension in imageExtensions
                        && (it.lastModified() > atlasModTime || createdAt > atlasModTime)
            }) return
        }

        // An image folder can optionally have a TexturePacker settings file
        val settingsFile = File(input, settingsFileName)
        val settings = if (settingsFile.exists())
            Json().fromJson(TexturePacker.Settings::class.java, settingsFile.reader(Charsets.UTF_8))
        else defaultSettings

        TexturePacker.process(settings, input, output, packFileName)
    }

    // Iterator providing all Image folders to process with the destination atlas name
    private data class ImageFolderResult(val folder: String, val atlasName: String)
    private fun imageFolders(parent: File) = sequence {
        for (folder in parent.listFiles()!!) {
            if (!folder.isDirectory) continue
            if (folder.nameWithoutExtension != imagesPathBase) continue
            val atlasName = if (folder.name == imagesPathBase) "game" else folder.extension
            yield(ImageFolderResult(folder.path, atlasName))
        }
    }
}