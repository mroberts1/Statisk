package fisk.staticsite.image

import fisk.staticsite.Config
import fisk.staticsite.Filter
import fisk.staticsite.FilterImage
import fisk.staticsite.Out
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import javax.imageio.IIOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ImageProcessor {

    enum class ImageSaveFormat {
        PNG,
        JPEG_HI,
        JPEG_MED,
        JPEG_LO
    }

    enum class ImageConversion {
        NONE,
        GREYSCALE_SCALE,
        COLOR_SCALE,
        DITHER
    }

    fun convertImage(saveDir: File, source: String): String?{
        return when(Config.imageConversion){
            ImageProcessor.ImageConversion.NONE -> null
            ImageProcessor.ImageConversion.COLOR_SCALE -> ImageProcessor.colorResize(saveDir, source)
            ImageProcessor.ImageConversion.GREYSCALE_SCALE -> ImageProcessor.greyscaleResize(saveDir, source)
            ImageProcessor.ImageConversion.DITHER -> ImageProcessor.ditherResize(saveDir, source, Config.ditherFilter, Config.threshold)
        }
    }

    private fun colorResize(saveDir: File, source: String): String? {
        Out.d("IMAGE PROCESSING: colorResize")
        val resized = resizedSource(saveDir, source)
        return when (resized) {
            null -> null
            else -> {
                val outputFile = File(saveDir, getProcessedFilename(source))
                saveProcessedImage(outputFile, resized)
                outputFile.name
            }
        }
    }

    private fun greyscaleResize(saveDir: File, source: String): String? {
        Out.d("IMAGE PROCESSING: greyscaleResize")
        val resized = resizedSource(saveDir, source)
        return when {
            resized != null -> {
                val greyscale = BufferedImage(resized.width, resized.height, BufferedImage.TYPE_BYTE_GRAY)
                val graphics = greyscale.graphics
                graphics.drawImage(resized, 0, 0, null)
                graphics.dispose()
                val outputFile = File(saveDir, getProcessedFilename(source))
                saveProcessedImage(outputFile, greyscale)
                outputFile.name
            }
            else -> null
        }
    }

    private fun ditherResize(saveDir: File, source: String, filter: Filter, threshold: Int): String? {

        val resized = resizedSource(saveDir, source)

        return when {
            resized != null -> {
                val destination = BufferedImage(resized.width, resized.height, BufferedImage.TYPE_BYTE_GRAY)
                val destinationImpl = FilterImageImpl(destination)
                filter.threshold(threshold).process(FilterImageImpl(resized), destinationImpl)
                val outputFile = File(saveDir, getProcessedFilename(source))
                saveProcessedImage(outputFile, destinationImpl.image)
                outputFile.name
            }
            else -> null
        }
    }

    // Internal Private Methods:

    private fun getProcessedFilename(source: String): String{
        return when(Config.imageFormat){
            ImageSaveFormat.PNG -> source.substring(0, source.lastIndexOf(".")) + "_processed.png"
            ImageSaveFormat.JPEG_HI, ImageSaveFormat.JPEG_MED, ImageSaveFormat.JPEG_LO -> source.substring(0, source.lastIndexOf(".")) + "_processed.jpeg"
        }
    }

    private fun saveProcessedImage(outputFile: File, image: BufferedImage){

        //Delete old files first
        deleteOldImage(outputFile)

        val writer = when (Config.imageFormat) {
            ImageSaveFormat.PNG -> ImageIO.getImageWritersByFormatName("png").next()
            else -> ImageIO.getImageWritersByFormatName("jpeg").next()
        }

        val param = writer?.defaultWriteParam

        if (param!= null && param.canWriteCompressed()) {
            Out.d("canWriteCompressed: true")
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT

            when(Config.imageFormat){
                ImageSaveFormat.PNG -> param.compressionQuality = 0.0f
                ImageSaveFormat.JPEG_HI -> param.compressionQuality = 0.85f
                ImageSaveFormat.JPEG_MED -> param.compressionQuality = 0.65f
                ImageSaveFormat.JPEG_LO -> param.compressionQuality = 0.50f
            }

        }else{
            Out.d("canWriteCompressed: false")
        }

        val os = FileOutputStream(outputFile)
        val ios = ImageIO.createImageOutputStream(os)
        writer?.output = ios
        writer?.write(null, IIOImage(image, null, null), param)
        writer?.dispose()
    }

    private fun resizedSource(saveDir: File, source: String): BufferedImage? {
        val sourceImage:BufferedImage?
        try {
            val convertImage = File(saveDir, source)
            if(convertImage.exists()){
                sourceImage = ImageIO.read(convertImage)
            }else{
                Out.d("Can't find image at ${convertImage.path} - skipping conversion")
                return null
            }
        }catch(exception: IIOException){
            Out.d("EXCEPTION reading image: $source in directory: ${saveDir.path}")
            return null
        }

        return when {
            sourceImage.width > Config.maxImageWidth -> resize(sourceImage, Config.maxImageWidth)
            else -> sourceImage
        }
    }

    private fun resize(src: BufferedImage, targetSize: Int): BufferedImage {
        var targetWidth = targetSize
        var targetHeight = targetSize
        val ratio = src.height.toFloat() / src.width.toFloat()
        if (ratio <= 1) { //square or landscape-oriented image
            targetHeight = Math.ceil((targetWidth.toFloat() * ratio).toDouble()).toInt()
        } else { //portrait image
            targetWidth = Math.round(targetHeight.toFloat() / ratio)
        }
        val bi = BufferedImage(targetWidth, targetHeight,
            if (src.transparency == Transparency.OPAQUE) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        )
        val g2d = bi.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null)
        g2d.dispose()
        return bi
    }

    private fun deleteOldImage(outputFile: File){
        val oldPNGFile = File(outputFile.path.replace(".jpeg", ".png"))
        if(oldPNGFile.exists()) oldPNGFile.delete()

        val oldJPEGFile = File(outputFile.path.replace(".png", ".jpeg"))
        if(oldJPEGFile.exists()) oldJPEGFile.delete()

    }

    //Used in Dither Filters only:
    class FilterImageImpl(val image: BufferedImage): FilterImage() {

        override var width: Int
            get() = image.width

            @Suppress("UNUSED_PARAMETER")
            set(value) {
                //unused'
            }

        override var height: Int
            get() = image.height

            @Suppress("UNUSED_PARAMETER")
            set(value) {
                //unused
            }

        override fun getPixel(x: Int, y: Int): Int {
            return image.getRGB(x, y)
        }

        override fun setPixel(x: Int, y: Int, colour: Int) {
            image.setRGB(x, y, colour)
        }
    }
}