package org.jlinda.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jlinda.core.Constants;
import org.jlinda.core.GeoPoint;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.geom.DemTile;
import org.jlinda.core.geom.TopoPhase;
import org.jlinda.core.utils.MathUtils;
import org.jlinda.nest.utils.BandUtilsDoris;
import org.jlinda.nest.utils.CplxContainer;
import org.jlinda.nest.utils.ProductContainer;
import org.jlinda.nest.utils.TileUtilsDoris;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OperatorMetadata(alias = "TopoPhaseRemoval",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Compute and subtract TOPO phase")
public final class SubtRefDemOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(interval = "(1, 10]",
            description = "Degree of orbit interpolation polynomial",
            defaultValue = "3",
            label = "Orbit Interpolation Degree")
    private int orbitDegree = 3;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec",
            label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(
            label = "Tile Extension [%]",
            description = "Define extension of tile for DEM simulation (optimization parameter).",
            defaultValue = "100")
    private String tileExtensionPercent = "100";

    @Parameter(description = "The topographic phase band name.",
            defaultValue = "topo_phase",
            label = "Topo Phase Band Name")
    private String topoPhaseBandName = "topo_phase";

    private ElevationModel dem = null;
    private double demNoDataValue = 0;
    private double demSamplingLat;
    private double demSamplingLon;
    private boolean demDefined = false;

    // source maps
    private Map<String, CplxContainer> masterMap = new HashMap<>();
    private Map<String, CplxContainer> slaveMap = new HashMap<>();

    // target maps
    private Map<String, ProductContainer> targetMap = new HashMap<>();

    private String[] polarisations;

    // operator tags
    public String productTag;

    private static final boolean CREATE_VIRTUAL_BAND = true;
    private static final String PRODUCT_SUFFIX = "_DInSAR";

    private boolean outputDEM = false;

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            checkUserInput();

            constructSourceMetadata();
            constructTargetMetadata();

            createTargetProduct();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void checkUserInput() {

        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfCoregisteredStack();
        validator.checkIfSLC();
        validator.checkIfTOPSARBurstProduct(false);

        productTag = "_ifg_srd";

        polarisations = OperatorUtils.getPolarisations(sourceProduct);
        if (polarisations.length == 0) {
            polarisations = new String[]{""};
        }
    }

    private static String getTOPSARTag(final Product sourceProduct) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
        final Band[] bands = sourceProduct.getBands();
        for (Band band:bands) {
            final String bandName = band.getName();
            if (bandName.contains(acquisitionMode)) {
                final int idx = bandName.indexOf(acquisitionMode);
                return bandName.substring(idx, idx + 6);
            }
        }
        return "";
    }

    private synchronized void defineDEM() throws IOException {
        if(demDefined)
            return;

        Resampling resampling = Resampling.BILINEAR_INTERPOLATION;
        final ElevationModelRegistry elevationModelRegistry;
        final ElevationModelDescriptor demDescriptor;

        if (externalDEMFile == null) {
            elevationModelRegistry = ElevationModelRegistry.getInstance();
            demDescriptor = elevationModelRegistry.getDescriptor(demName);

            if (demDescriptor == null) {
                throw new OperatorException("The DEM '" + demName + "' is not supported.");
            }

            dem = demDescriptor.createDem(resampling);
            if (dem == null) {
                throw new OperatorException("The DEM '" + demName + "' has not been installed.");
            }

            demNoDataValue = demDescriptor.getNoDataValue();
            demSamplingLat = demDescriptor.getTileWidthInDegrees() * (1.0f / demDescriptor.getTileWidth()) * Constants.DTOR;
            demSamplingLon = demSamplingLat;
        }

        if (externalDEMFile != null) { // if external DEM file is specified by user
            dem = new FileElevationModel(externalDEMFile, resampling.getName(), externalDEMNoDataValue);
            demName = externalDEMFile.getPath();
            demNoDataValue = externalDEMNoDataValue;

            // assume the same sampling in X and Y direction?
            try {
                demSamplingLat = (dem.getGeoPos(new PixelPos(1, 0)).getLat() - dem.getGeoPos(new PixelPos(0, 0)).getLat()) * Constants.DTOR;
                demSamplingLon = (dem.getGeoPos(new PixelPos(0, 1)).getLat() - dem.getGeoPos(new PixelPos(0, 0)).getLat()) * Constants.DTOR;
            } catch (Exception e) {
                throw new OperatorException("The DEM '" + demName + "' cannot be properly interpreted.");
            }
        }

        demDefined = true;
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceMaster/sourceSlave name tags
        final String masterTag = "ifg";
        final String slaveTag = "ifg";

        // get sourceMaster & sourceSlave MetadataElement
        final MetadataElement masterMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String slaveMetadataRoot = AbstractMetadata.SLAVE_METADATA_ROOT;

        // organize metadata

        // put sourceMaster metadata into the masterMap
        metaMapPut(masterTag, masterMeta, sourceProduct, masterMap);

        // plug sourceSlave metadata into slaveMap
        MetadataElement[] slaveRoot = sourceProduct.getMetadataRoot().getElement(slaveMetadataRoot).getElements();
        for (MetadataElement meta : slaveRoot) {
            metaMapPut(slaveTag, meta, sourceProduct, slaveMap);
        }
    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final Map<String, CplxContainer> map) throws Exception {

        for (String polarisation : polarisations) {
            final String pol = polarisation.isEmpty() ? "" : '_' + polarisation.toUpperCase();

            // map key: ORBIT NUMBER
            String mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT) + pol;

            // metadata: construct classes and define bands
            final String date = OperatorUtils.getAcquisitionDate(root);
            final SLCImage meta = new SLCImage(root, product);
            final Orbit orbit = new Orbit(root, orbitDegree);

            // TODO: resolve multilook factors
            meta.setMlAz(1);
            meta.setMlRg(1);

            Band bandReal = null;
            Band bandImag = null;

            for (String bandName : product.getBandNames()) {
                if (bandName.contains(tag) && bandName.contains(date)) {
                    if (pol.isEmpty() || bandName.contains(pol)) {
                        final Band band = product.getBand(bandName);
                        if (BandUtilsDoris.isBandReal(band)) {
                            bandReal = band;
                        } else if (BandUtilsDoris.isBandImag(band)) {
                            bandImag = band;
                        }
                    }
                }
            }

            map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
        }
    }

    private void constructTargetMetadata() {

        for (String keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (String keySlave : slaveMap.keySet()) {
                final CplxContainer slave = slaveMap.get(keySlave);

                if (master.polarisation.equals(slave.polarisation)) {
                    // generate name for product bands
                    String productName = keyMaster + '_' + keySlave;

                    final ProductContainer product = new ProductContainer(productName, master, slave, true);

                    // put ifg-product bands into map
                    targetMap.put(productName, product);
                }
            }
        }
    }

    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {
            final List<String> targetBandNames = new ArrayList<>();
            final ProductContainer container = targetMap.get(key);
            final CplxContainer master = container.sourceMaster;
            final CplxContainer slave = container.sourceSlave;

            final String pol = master.polarisation.isEmpty() ? "" : '_' + master.polarisation.toUpperCase();
            final String tag = pol + '_' + master.date + '_' + slave.date;

            String targetBandName_I = 'i' + tag;
            Band iBand = targetProduct.addBand(targetBandName_I, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.REAL, iBand.getName());
            iBand.setUnit(Unit.REAL);
            targetBandNames.add(iBand.getName());

            String targetBandName_Q = 'q' + tag;
            Band qBand = targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT32);
            container.addBand(Unit.IMAGINARY, qBand.getName());
            qBand.setUnit(Unit.IMAGINARY);
            targetBandNames.add(qBand.getName());

            if (CREATE_VIRTUAL_BAND) {
                String countStr = productTag + tag;

                Band intensityBand = ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBand(targetBandName_I),
                        targetProduct.getBand(targetBandName_Q), countStr);
                targetBandNames.add(intensityBand.getName());

                Band phaseBand = ReaderUtils.createVirtualPhaseBand(targetProduct, targetProduct.getBand(targetBandName_I),
                        targetProduct.getBand(targetBandName_Q), countStr);
                targetBandNames.add(phaseBand.getName());

                targetProduct.setQuicklookBandName(phaseBand.getName());
            }

            if (container.subProductsFlag) {
                String topoBandName = topoPhaseBandName + tag;
                Band topoBand = targetProduct.addBand(topoBandName, ProductData.TYPE_FLOAT32);
                container.addBand(Unit.PHASE, topoBand.getName());
                topoBand.setNoDataValue(demNoDataValue);
                topoBand.setUnit(Unit.PHASE);
                topoBand.setDescription("topographic_phase");
                targetBandNames.add(topoBand.getName());

                if (outputDEM) {
                    Band elevBand = targetProduct.addBand("elevation", ProductData.TYPE_FLOAT32);
                    elevBand.setNoDataValue(demNoDataValue);
                    elevBand.setUnit(Unit.METERS);
                    elevBand.setDescription("elevation");
                    targetBandNames.add(elevBand.getName());
                }
            }

            // copy other bands through
            for(Band srcBand : sourceProduct.getBands()) {
                if(srcBand instanceof VirtualBand) {
                    continue;
                }

                String srcBandName = srcBand.getName();
                if(srcBandName.endsWith(tag)) {
                    if (srcBandName.startsWith("coh") || srcBandName.startsWith("elev")) {
                        Band band = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, true);
                        targetBandNames.add(band.getName());
                    }
                }
            }

            String slvProductName = StackUtils.findOriginalSlaveProductName(sourceProduct, container.sourceSlave.realBand);
            StackUtils.saveSlaveProductBandNames(targetProduct, slvProductName,
                                                 targetBandNames.toArray(new String[targetBandNames.size()]));
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            int y0 = targetRectangle.y;
            int yN = y0 + targetRectangle.height - 1;
            int x0 = targetRectangle.x;
            int xN = targetRectangle.x + targetRectangle.width - 1;
            final Window tileWindow = new Window(y0, yN, x0, xN);

            if(!demDefined) {
                defineDEM();
            }

            DemTile demTile = getDEMTile(
                    tileWindow, targetMap, dem, demNoDataValue, demSamplingLat, demSamplingLon, tileExtensionPercent);
            if(demTile == null) {
                return;
            }

            Band topoPhaseBand, targetBand_I, targetBand_Q, elevBand;

            // TODO: smarter extension of search space : foreshortening extension? can I calculate how bit tile I
            // need (extra space) for the coverage, taking into the consideration only height of the tile?
            for (String ifgKey : targetMap.keySet()) {

                ProductContainer product = targetMap.get(ifgKey);

                TopoPhase topoPhase = computeTopoPhase(product, tileWindow, demTile, outputDEM);

                /// check out results from source ///
                Tile tileReal = getSourceTile(product.sourceMaster.realBand, targetRectangle);
                Tile tileImag = getSourceTile(product.sourceMaster.imagBand, targetRectangle);
                ComplexDoubleMatrix complexIfg = TileUtilsDoris.pullComplexDoubleMatrix(tileReal, tileImag);

                final ComplexDoubleMatrix cplxTopoPhase = new ComplexDoubleMatrix(
                        MatrixFunctions.cos(new DoubleMatrix(topoPhase.demPhase)),
                        MatrixFunctions.sin(new DoubleMatrix(topoPhase.demPhase)));

                complexIfg.muli(cplxTopoPhase.conji());

                /// commit to target ///
                targetBand_I = targetProduct.getBand(product.getBandName(Unit.REAL));
                Tile tileOutReal = targetTileMap.get(targetBand_I);
                TileUtilsDoris.pushDoubleMatrix(complexIfg.real(), tileOutReal, targetRectangle);

                targetBand_Q = targetProduct.getBand(product.getBandName(Unit.IMAGINARY));
                Tile tileOutImag = targetTileMap.get(targetBand_Q);
                TileUtilsDoris.pushDoubleMatrix(complexIfg.imag(), tileOutImag, targetRectangle);

                topoPhaseBand = targetProduct.getBand(product.getBandName(Unit.PHASE));
                Tile tileOutTopoPhase = targetTileMap.get(topoPhaseBand);
                TileUtilsDoris.pushDoubleArray2D(topoPhase.demPhase, tileOutTopoPhase, targetRectangle);

                if (outputDEM) {
                    elevBand = targetProduct.getBand("elevation");
                    Tile tileElevBand = targetTileMap.get(elevBand);
                    TileUtilsDoris.pushDoubleArray2D(topoPhase.elevation, tileElevBand, targetRectangle);
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    public static DemTile getDEMTile(final org.jlinda.core.Window tileWindow,
                                     final Map<String, ProductContainer> targetMap,
                                     final ElevationModel dem,
                                     final double demNoDataValue,
                                     final double demSamplingLat,
                                     final double demSamplingLon,
                                     final String tileExtensionPercent) {

        try {
            ProductContainer mstContainer = targetMap.values().iterator().next();

            // compute tile geo-corners ~ work on ellipsoid
            GeoPoint[] geoCorners = org.jlinda.core.utils.GeoUtils.computeCorners(
                    mstContainer.sourceMaster.metaData, mstContainer.sourceMaster.orbit, tileWindow);

            // get corners as DEM indices
            PixelPos[] pixelCorners = new PixelPos[2];
            pixelCorners[0] = dem.getIndex(new GeoPos(geoCorners[0].lat, geoCorners[0].lon));
            pixelCorners[1] = dem.getIndex(new GeoPos(geoCorners[1].lat, geoCorners[1].lon));

            final int x0DEM = (int)Math.round(pixelCorners[0].x);
            final int y0DEM = (int)Math.round(pixelCorners[0].y);
            final int x1DEM = (int)Math.round(pixelCorners[1].x);
            final int y1DEM = (int)Math.round(pixelCorners[1].y);
            final Rectangle demTileRect = new Rectangle(x0DEM, y0DEM, x1DEM - x0DEM + 1, y1DEM - y0DEM + 1);

            // get max/min height of tile ~ uses 'fast' GCP based interpolation technique
            final double[] tileHeights = computeMaxHeight(
                    pixelCorners, demTileRect, tileExtensionPercent, dem, demNoDataValue);

            // compute extra lat/lon for dem tile
            GeoPoint geoExtent = org.jlinda.core.utils.GeoUtils.defineExtraPhiLam(tileHeights[0], tileHeights[1],
                    tileWindow, mstContainer.sourceMaster.metaData,
                    mstContainer.sourceMaster.orbit);

            // extend corners
            geoCorners = org.jlinda.core.utils.GeoUtils.extendCorners(geoExtent, geoCorners);

            // update corners
            pixelCorners[0] = dem.getIndex(new GeoPos(geoCorners[0].lat, geoCorners[0].lon));
            pixelCorners[1] = dem.getIndex(new GeoPos(geoCorners[1].lat, geoCorners[1].lon));

            pixelCorners[0] = new PixelPos(Math.floor(pixelCorners[0].x), Math.floor(pixelCorners[0].y));
            pixelCorners[1] = new PixelPos(Math.ceil(pixelCorners[1].x), Math.ceil(pixelCorners[1].y));

            GeoPos upperLeftGeo = dem.getGeoPos(pixelCorners[0]);

            int nLatPixels = (int) Math.abs(pixelCorners[1].y - pixelCorners[0].y);
            int nLonPixels = (int) Math.abs(pixelCorners[1].x - pixelCorners[0].x);

            if(!upperLeftGeo.isValid()) {
                return null;
            }

            DemTile demTile = new DemTile(upperLeftGeo.lat * org.jlinda.core.Constants.DTOR,
                                          upperLeftGeo.lon * org.jlinda.core.Constants.DTOR,
                                          nLatPixels, nLonPixels, Math.abs(demSamplingLat),
                                          Math.abs(demSamplingLon), (long)demNoDataValue);

            int startX = (int) pixelCorners[0].x;
            int endX = startX + nLonPixels;
            int startY = (int) pixelCorners[0].y;
            int endY = startY + nLatPixels;

            double[][] elevation = new double[nLatPixels][nLonPixels];
            for (int y = startY, i = 0; y < endY; y++, i++) {
                for (int x = startX, j = 0; x < endX; x++, j++) {
                    try {
                        double elev = dem.getSample(x, y);
                        if (Double.isNaN(elev)) {
                            elev = demNoDataValue;
                        }
                        elevation[i][j] = elev;
                    } catch (Exception e) {
                        elevation[i][j] = demNoDataValue;
                    }
                }
            }

            demTile.setData(elevation);

            return demTile;
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    public static TopoPhase computeTopoPhase(
            final ProductContainer product, final Window tileWindow, final DemTile demTile, final boolean outputDEM) {

        try {
            final TopoPhase topoPhase = new TopoPhase(product.sourceMaster.metaData, product.sourceMaster.orbit,
                    product.sourceSlave.metaData, product.sourceSlave.orbit, tileWindow, demTile);

            topoPhase.radarCode();

            topoPhase.gridData(outputDEM);

            return topoPhase;

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private static double[] computeMaxHeight(
            final PixelPos[] corners, final Rectangle rectangle, final String tileExtensionPercent,
            final ElevationModel dem, final double demNoDataValue) throws Exception {

        /* Notes:
          - The scaling and extensions of extreme values of DEM tiles has to be performed to guarantee the overlap
            between SAR and DEM tiles, and avoid blanks in the simulated Topo phase.

          - More conservative, while also more reliable parameters are introduced that guarantee good results even
            in some extreme cases.

          - Parameters are defined for the reliability, not(!) the performance.
         */

        int tileExtPercent = Integer.parseInt(tileExtensionPercent);
        final float extraTileX = (float) (1 + tileExtPercent / 100.0); // = 1.5f
        final float extraTileY = (float) (1 + tileExtPercent / 100.0); // = 1.5f
        final float scaleMaxHeight = (float) (1 + tileExtPercent/ 100.0); // = 1.25f

        double[] heightArray = new double[2];

        // double square root : scales with the size of tile
        final int numberOfPoints = (int) (10 * Math.sqrt(Math.sqrt(rectangle.width * rectangle.height)));

        // extend tiles for which statistics is computed
        final int offsetX = (int) (extraTileX * rectangle.width);
        final int offsetY = (int) (extraTileY * rectangle.height);

        // define window
        final Window window = new Window((long)(corners[0].y - offsetY),
                                         (long)(corners[1].y + offsetY),
                                         (long)(corners[0].x - offsetX),
                                         (long)(corners[1].x + offsetX));

        // distribute points
        final int[][] points = MathUtils.distributePoints(numberOfPoints, window);
        final ArrayList<Double> heights = new ArrayList();

        // then for number of extra points
        for (int[] point : points) {
            Double height = dem.getSample(point[1], point[0]);
            if (!Double.isNaN(height) && !height.equals(demNoDataValue)) {
                heights.add(height);
            }
        }

        // get max/min and add extras ~ just to be sure
        if (heights.size() > 2) {
            // set minimum to 'zero', eg, what if there's small lake in tile?
            // heightArray[0] = Collections.min(heights);
            heightArray[0] = Collections.min(heights);
            heightArray[1] = Collections.max(heights) * scaleMaxHeight;
        } else { // if nodatavalues return 0s ~ tile in the sea
            heightArray[0] = 0;
            heightArray[1] = 0;
        }

        return heightArray;
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SubtRefDemOp.class);
        }
    }
}
