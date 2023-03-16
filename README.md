# DNA-Foci-Quantification
Groovy script which uses detects nuclei and DNA foci and quantifies size and intensity in two channels 

## Pre-requisites:
To use this script you need to have Cellpose installed on your computer and the BIOP Cellpose wrapper installed in your IJ plugins. 
To install Cellpose to use with ImageJ on a windows computer;
- Install Anaconda from https://www.anaconda.com/products/distribution
- Add Anaconda to path https://www.datacamp.com/tutorial/installing-anaconda-windows, you need to have admin rights on your computer. 
- Install Cellpose https://github.com/MouseLand/cellpose#local-installation
- Add the ijl-utilities-wrapper jar to your ImageJ plugins folder (download the jar here: https://mvnrepository.com/artifact/ch.epfl.biop/ijl-utilities-wrappers/0.3.19)
- BIOP should appear as a plugin, navigate to BIOP -> Cellpose -> Define Env and prefs
- Set CellposeEnvDirectory to your Anaconda\envs\cellpose . On my computer this is C:\Users\username\Anaconda3\envs\cellpose (if this doesn’t exist you have not managed to install Cellpose correctly).
- Set EnvType to conda
- Only UseGpu should be ticked
- You can now use Cellpose through ImageJ

## Running the script
- Open the script in ImageJ File-> New…-> Script
- At the bottom of the script delete the annotated code (this is used to run the script in the development environment)
- Select Run
- You will be asked to select a folder, it is expected that the folder will contain .czi image stacks with 3 channels, channel 2 = MeCP2 and channel 3 = DAPI. 
- The plugin creates maximum and mean z-projections of the image stacks. Cellpose is run on the mean z-project of the DAPI channel using the ‘cyto’ model with an estimated diameter of 200 pixels. 
- Nuclear ROIs are determined from the Cellpose masks. A Huang threshold is applied in the MeCP2 maximum projection to determine which cells are expressing MeCP2, in these cells a Yen threshold is then applied to the DAPI maximum projection to detect DNA-dense Foci. 
- The size and number of foci per nucleus and the intensities in both channels for both the foci and the nucleoplasm are output to a results file. A further results file showing per nucleus averages and output images for the two maximum projections with an overlay of the analysed regions are also saved.
