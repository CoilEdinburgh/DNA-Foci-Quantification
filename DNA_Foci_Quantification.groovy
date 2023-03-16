import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.Roi
import ij.io.DirectoryChooser
import ij.plugin.frame.RoiManager
import ij.process.ImageProcessor
import ij.process.ImageStatistics
import io.scif.services.DatasetIOService
import net.imagej.Dataset
import net.imagej.axis.Axes
import net.imagej.ops.Op
import net.imagej.ops.OpService
import net.imagej.patcher.LegacyInjector
import net.imagej.roi.ROIService
import net.imglib2.FinalInterval
import net.imglib2.IterableInterval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.Img
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.roi.MaskInterval
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.view.IntervalView
import net.imglib2.view.Views
import org.scijava.Context
import org.scijava.command.Command
import org.scijava.command.CommandService
import org.scijava.plugin.Parameter
import org.scijava.ui.UIService

import java.awt.*
import java.nio.file.Paths
import java.text.SimpleDateFormat

class DNA_Foci_Quantification implements Command {

    @Parameter
    private DatasetIOService datasetIOService

    @Parameter
    private OpService ops

   // @Parameter
   // private LegacyService legacyService

    @Parameter
    private UIService uiService

    @Parameter
    private ROIService roiService

    String filePath

    @Override
    void run() {
        //Open a directory
        def directoryChooser = new DirectoryChooser("Open Directory")
        def directory = directoryChooser.getDirectory().toString()
        File dir = new File(directory)
        def files = dir.listFiles()

        //Create a new folder for the Output images and results files
        def newDirectory = directory + "Output_DAPI_thres"
        filePath = Paths.get(directory, "Output_DAPI_thres").toString()
        new File(newDirectory).mkdir()

        //Make the results file in the Output folder if it does not already exist
        try {
            MakeResults("")
            MakeResults("Average_")
        } catch (IOException e) {
            e.printStackTrace()
        }

        //Open an ROI Manager
        def roiManager = new RoiManager()

        //For each File in the current directory
        def pixelSize = 0.0496144

        for (int m = 0; m < files.length; m++) {
            String id = files[m].getPath()
            //Check it is a .czi file


            if (id.contains(".czi")) {
                Dataset currentData = null
                //Open the file
                try {
                    currentData = datasetIOService.open(id)
                } catch (IOException ignore) {
                    ignore.printStackTrace()
                }
                //Start a new entry in the results file
                try {
                    NewFileResults("", id)
                    NewFileResults("Average_", id)
                } catch (IOException ignore) {
                    ignore.printStackTrace()
                }

                //Extract the GFP and DAPI Channels
                def channelAxes = currentData.dimensionIndex(Axes.CHANNEL)
                def channelGFP = splitChannel(currentData, channelAxes).get(1)
                def channelDAPI = splitChannel(currentData, channelAxes).get(2)

                //Make Z-projections
                def zimageGFP = zProjector(channelGFP as Img, 2, "stats.max")
                def zimageDAPI_mean = zProjector(channelDAPI as Img, 2, "stats.mean")
                def zimageDAPI = zProjector(channelDAPI as Img, 2, "stats.max")

                //Convert Max Projections to 16-bit images (thresholding works better)
                zimageDAPI = ops.convert().int16(zimageDAPI)
                zimageGFP = ops.convert().int16(zimageGFP)

                //Filter the GFP mean projection with a gaussian filter (sigma = 5) then run Cellpose on this image
                def gauss = ops.filter().gauss(zimageDAPI_mean, 5)
                uiService.show(gauss)
                IJ.run("Cellpose Advanced", "diameter=" + 200 + " cellproba_threshold=" + 0.0 + " flow_threshold=" + 0.4
                        + " anisotropy=" + 5.0 + " diam_threshold=" + 12.0 + " model=" + "cyto" + " nuclei_channel=" + -1
                        + " cyto_channel=" + 1 + " dimensionmode=" + "2D" + " stitch_threshold=" + -1 + " omni=" + false
                        + " cluster=" + false + " additional_flags=" + " ")

                //Get ROIs of nuclear outlines from the mask created by cellpose and save these ROIs
                getROIsfromMask()
                Roi[] OutlineRois = roiManager.getRoisAsArray()
                roiManager.reset()

                //Make Output Images and setup image processor (for drawing rois)
                ImagePlus Green = ImageJFunctions.wrap(zimageGFP, "Green")
                ImagePlus Blue = ImageJFunctions.wrap(zimageDAPI, "Blue")
                ImagePlus Overlay = IJ.createImage("Overlay", "16-bit black", Blue.getWidth(), Blue.getHeight(), 1)
                Overlay.show()
                Green.show()
                Blue.show()
                ImageProcessor ip = Overlay.getProcessor()
                Font font = new Font("SansSerif", Font.BOLD, 20)
                ip.setFont(font)
                ip.setColor(Color.white)

                //for each outline ROI
                for (int j = 0; j < OutlineRois.length; j++) {

                    //Find the area of the outline and its bounding rectangle
                    double cellArea = OutlineRois[j].getStatistics().area
                    def rect = OutlineRois[j].getBoundingRect()

                    if (rect.maxX < 927 && rect.maxY < 927 && rect.maxX > 1 && rect.maxY > 1) {
                        //if the outline isn't touching the edge of the image set count to zero (spot counter)
                        int count = 0

                        //Create a mask interval from the ROI and apply to the DAPI and GFP channels
                        MaskInterval cellInterval = roiService.toMaskInterval(OutlineRois[j])
                        IterableInterval blueCell = Views.interval(zimageDAPI, cellInterval)
                        IterableInterval greenCell = Views.interval(zimageGFP, cellInterval)

                        //Apply ops to find the mean intensity in each channel
                        Object meanBlueCell = ops.run("stats.mean", blueCell)
                        Object meanGreenCell = ops.run("stats.mean", greenCell)

                        //Create a results list
                        ArrayList results = new ArrayList()

                        //Find a threshold in the GFP channel
                        def histogram = ops.image().histogram((IterableInterval) zimageGFP, 256)
                        def threshold = ops.threshold().huang(histogram).realDouble
                        double meanGreenCellDouble = Double.parseDouble(String.valueOf(meanGreenCell))

                        //If the cell average intensity in the GFP channel is above the threshold then analyse that cell
                        if (meanGreenCellDouble > threshold) {

                            //Draw and number the Outline on the output image
                            ip.setFont(font)
                            String number = String.valueOf((j + 1))
                            ip.drawString(number, (int) OutlineRois[j].getContourCentroid()[0], (int) OutlineRois[j].getContourCentroid()[1])
                            ip.draw(OutlineRois[j])

                            //Find the spots in the DAPI channel using the outlined area to set the threshold
                            findspots(zimageDAPI, OutlineRois[j])
                            Roi[] spots = roiManager.getRoisAsArray()
                            roiManager.reset()

                            for (int k = 0; k < spots.length; k++) {

                                //For each spot check if it is inside the current Outline
                                int centerX = (int) spots[k].getContourCentroid()[0]
                                int centerY = (int) spots[k].getContourCentroid()[1]
                                if (OutlineRois[j].contains(centerX, centerY)) {
                                    MaskInterval maskInterval = roiService.toMaskInterval(spots[k])

                                    //Apply the MaskInterval to the Mean Z-projections to get an IterableInterval just in the ROI
                                    IterableInterval blueSpot = Views.interval(zimageDAPI, maskInterval)
                                    IterableInterval greenSpot = Views.interval(zimageGFP, maskInterval)

                                    //Apply ops to find the mean intensity in each channel and the area of the spot
                                    double areaSpot = spots[k].getStatistics().area
                                    Object meanBlue = ops.run("stats.mean", blueSpot)
                                    Object meanGreen = ops.run("stats.mean", greenSpot)

                                    //Create a results array, to hold the outline_number, spot_number, DAPI_intensity_spot,
                                    // DAPI_intensity_outline,GFP_intensity_spot, GFP_intensity_outline,outline_area, spot_area
                                    double[] resultsk = new double[8]
                                    resultsk[0] = j + 1
                                    resultsk[1] = k
                                    resultsk[2] = Double.parseDouble(String.valueOf(meanBlue))
                                    resultsk[3] = Double.parseDouble(String.valueOf(meanBlueCell))
                                    resultsk[4] = Double.parseDouble(String.valueOf(meanGreen))
                                    resultsk[5] = Double.parseDouble(String.valueOf(meanGreenCell))
                                    resultsk[6] = cellArea
                                    resultsk[7] = areaSpot
                                    results.add(resultsk)
                                    count = count + 1

                                    //Draw and number the spot on the output image
                                    Font smallfont = new Font("SansSerif", Font.BOLD, 10)
                                    ip.setFont(smallfont)
                                    ip.setColor(Color.white)
                                    number = String.valueOf(count)
                                    ip.drawString(number, (int) spots[k].getContourCentroid()[0], (int) spots[k].getContourCentroid()[1])
                                    ip.draw(spots[k])
                                }
                            }
                        }

                        //If a spot has been found in the outline
                        if (count > 2) {

                            //Sum up the areas and intensities of the spots in both channels
                            double sumSpotsBlue = 0
                            double sumSpotsGreen = 0
                            double sumIntensityBlue = 0
                            double sumIntensityGreen = 0
                            double areaSpots = 0
                            for (int n = 0; n < count; n++) {
                                double[] line = results.get(n)
                                sumSpotsBlue = sumSpotsBlue + line[2] * line[7]
                                sumSpotsGreen = sumSpotsGreen + line[4] * line[7]
                                sumIntensityBlue = sumIntensityBlue + line[2]
                                sumIntensityGreen = sumIntensityGreen + line[4]
                                areaSpots = areaSpots + line[7]
                            }

                            //Calculate the intensity of the background - the intensity of the spots
                            double backgroundBlue = (double) (results.get(0)[3] * results.get(0)[6] - sumSpotsBlue) / (results.get(0)[6] - areaSpots)
                            double backgroundGreen = (double) (results.get(0)[5] * results.get(0)[6] - sumSpotsGreen) / (results.get(0)[6] - areaSpots)

                            //Add individual spots to results
                            for (int p = 0; p < count; p++) {
                                double[] resultsLine = results.get(p)
                                try {
                                    AddToResults("", resultsLine[0], resultsLine[1], resultsLine[2], backgroundBlue, resultsLine[4], backgroundGreen,
                                            resultsLine[6] * pixelSize * pixelSize, resultsLine[7] * pixelSize * pixelSize, resultsLine[2] / backgroundBlue, resultsLine[4] / backgroundGreen)
                                } catch (IOException e) {
                                    e.printStackTrace()
                                }
                            }
                            //Add outline averages to results
                            try {
                                AddToResults("Average_", (j + 1), count, sumIntensityBlue / count, backgroundBlue, sumIntensityGreen / count, backgroundGreen,
                                        pixelSize * pixelSize * results.get(0)[6], pixelSize * pixelSize * areaSpots / count, sumIntensityBlue / (count * backgroundBlue),
                                        sumIntensityGreen / (count * backgroundGreen))
                            } catch (IOException e) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

                //Update the output image merge channels and save the image
                Overlay.updateAndDraw()
                IJ.run("Merge Channels...", "c2=[Green] c3=[Blue] c7=[Overlay] create")
                int start = id.lastIndexOf("\\")+1
                int end = id.lastIndexOf(".czi")
                IJ.log(id.substring(start, end))
                WindowManager.getCurrentImage().setTitle(id.substring(start, end))
                ImagePlus saveImage = WindowManager.getImage(id.substring(start, end))
                IJ.run(saveImage, "Enhance Contrast", "saturated=0.35")
                String CreateName = Paths.get(filePath, saveImage.getTitle()).toString()
                IJ.saveAs(saveImage, "Tiff", CreateName)

                //Close everything
                IJ.run("Close All", "")
//
            }
        }
    }

    void getROIsfromMask() {

        //Gets the current image (the mask output from cellpose)
        ImagePlus mask = WindowManager.getCurrentImage()
        ImageStatistics stats = mask.getStatistics()
        //For each ROI (intensity per cell mask is +1 to intensity
        for (int i = 1; i < stats.max + 1; i++) {
            //Set the threshold for the cell and use analyse particles to add to ROI manager
            IJ.setThreshold(mask, i, i)
            IJ.run(mask, "Analyze Particles...", "add")
        }
    }

    void NewFileResults(String name, String id) throws IOException {

        //Create a new entry in the results file, headed with the filename and with column headers
        String CreateName = Paths.get(filePath, "Results_" + name + "OUTPUT.txt").toString()
        File resultsFile = new File(CreateName)
        if (!resultsFile.exists()) {
            resultsFile.createNewFile()
        }
        try {
            FileWriter fileWriter = new FileWriter(CreateName, true)
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)
            int start = id.lastIndexOf("\\")+1
            int end = id.lastIndexOf(".czi")
            bufferedWriter.newLine()
            bufferedWriter.write(id.substring(start, end))
            bufferedWriter.newLine()
            bufferedWriter.write("Cell,Spots,DAPI,DAPI_Background,GFP,GFP_Background,Cell_Area(um2),SpotArea(um2),DAPI_ratio,GFP_ratio")
            bufferedWriter.close()
        } catch (IOException ignored) {
            System.out.println(
                    "Error writing to file '" + CreateName + "'")
        }
    }//

    void AddToResults(String name, double cell, spot, double DAPI, double DAPIcell, double GFP, double GFPcell, double areaCell,
                      double areaSpot, double DAPI_ratio, double GFP_ratio) throws IOException {
        //Add the outputs to the results
        String CreateName = Paths.get(filePath, "Results_" + name + "OUTPUT.txt").toString()
        File resultsFile = new File(CreateName)
        if (!resultsFile.exists()) {
            resultsFile.createNewFile()
        }
        try {
            FileWriter fileWriter = new FileWriter(CreateName, true)
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)
            bufferedWriter.newLine()
            bufferedWriter.write(cell + "," + spot + "," + DAPI + "," + DAPIcell + "," + GFP + "," + GFPcell + "," + areaCell + "," + areaSpot + "," + DAPI_ratio + "," + GFP_ratio)
            bufferedWriter.close()
        } catch (IOException ignored) {
            System.out.println(
                    "Error writing to file '" + CreateName + "'")
        }
    }//

    void MakeResults(String name) throws IOException {
        //Create or append results file with the time and date of the analysis
        Date date = new Date() // This object contains the current date value
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss")
        String CreateName = Paths.get(filePath, "Results_" + name + "OUTPUT.txt").toString()
        try {
            FileWriter fileWriter = new FileWriter(CreateName, true)
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)
            bufferedWriter.newLine()
            bufferedWriter.newLine()
            bufferedWriter.write(formatter.format(date))
            bufferedWriter.newLine()
            bufferedWriter.close()
        } catch (IOException ignored) {
            System.out.println(
                    "Error writing to file '" + CreateName + "'")
        }
    }

    void findspots(RandomAccessibleInterval channel, Roi roi) {
        //Use input Roi to create a mask interval
        MaskInterval maskInterval = roiService.toMaskInterval(roi)
        //Create an interval view of the mask interval
        def interval = Views.interval(channel, maskInterval)
        //Use ops to find and apply the yen threshold
        def histogram = ops.image().histogram((IterableInterval) interval, 256)
        def threshold = ops.threshold().yen(histogram)
        def thres = ops.threshold().apply((IterableInterval) channel, threshold)
        //Invert the image to create a dark background
        IterableInterval invert = ops.create().img(thres)
        ops.image().invert(invert, thres)
        uiService.show(thres)
        //Convert to ImagePlus and Analyse thresholded particles and add ROIs to ROI manager
        def thresIP = ImageJFunctions.wrap(invert as RandomAccessibleInterval, "thresholded")
        IJ.run(thresIP, "Analyze Particles...", "size=100-Infinity pixel include add")
    }

    ArrayList<IntervalView> splitChannel(Dataset dataset, int channelDim) {
        // how many channels do we have?
        long numChannels = dataset.dimensionsAsLongArray().length

        def output = new ArrayList<>()
        // iterate over all channels
        for (int channelIndex = 0; channelIndex < numChannels; channelIndex++) {
            def inputChannel = ops.transform().hyperSliceView(dataset, channelDim, channelIndex)
            output.add(inputChannel)
        }
        return output
    }

    Img zProjector(Img image, int dim, String operation) {

        //Gets the XY dimensions of the image
        FinalInterval interval = new FinalInterval(image.dimension(0), image.dimension(1))
        //Creates a single plane image blank image to hold the result
        Img<DoubleType> projected = ops.create().img(interval)
        //Calculates mean/max/min values for all pixels in z stack
        Op statsOp = ops.op(operation, image)
        //projects the original image and stores it in the newly created blank image
        ops.run("project", projected, image, statsOp, dim)
        //shows the new projected image.
        uiService.show(projected)
        return (Img) projected
    }


    static {                    //delete when running in script editor
        LegacyInjector.preinit()//delete when running in script editor
    }                           //delete when running in script editor


    static void main(String[] args) {
        def context = (Context) IJ.runPlugIn("org.scijava.Context", "")
        def commandService = context.getService(CommandService.class)
        def uIService = context.getService(UIService.class)//delete when running in script editor
        uIService.showUI() //delete when running in script editor
        commandService.run(DNA_Foci_Quantification.class, true)

    }
}


