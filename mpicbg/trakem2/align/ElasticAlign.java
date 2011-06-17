/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package mpicbg.trakem2.align;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.ij.SIFT;
import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Spring;
import mpicbg.models.SpringMesh;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.Transforms;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class ElasticAlign
{
	
	final static private class Triple< A, B, C >
	{
		final public A a;
		final public B b;
		final public C c;
		
		Triple( final A a, final B b, final C c )
		{
			this.a = a;
			this.b = b;
			this.c = c;
		}
	}
	
	final static private class Param implements Serializable
	{
		private static final long serialVersionUID = 3816564377727147658L;

		final static private class ParamPointMatch implements Serializable
		{
			private static final long serialVersionUID = 2605327789375628874L;
			
			final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();
			
			/**
			 * Closest/next closest neighbor distance ratio
			 */
			public float rod = 0.92f;
			
			@Override
			public boolean equals( Object o )
			{
				if ( getClass().isInstance( o ) )
				{
					final ParamPointMatch oppm = ( ParamPointMatch )o;
					return 
						oppm.sift.equals( sift ) &
						oppm.rod == rod;
				}
				else
					return false;
			}
			
			public boolean clearCache = false;
		}
		
		final public ParamPointMatch ppm = new ParamPointMatch();
		
		/**
		 * Maximal accepted alignment error in px
		 */
		public float maxEpsilon = 25.0f;
		
		/**
		 * Inlier/candidates ratio
		 */
		public float minInlierRatio = 0.0f;
		
		/**
		 * Minimal absolute number of inliers
		 */
		public int minNumInliers = 12;
		
		/**
		 * Transformation models for choice
		 */
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine", "Perspective" };
		public int modelIndex = 3;
		
		/**
		 * Ignore identity transform up to a given tolerance
		 */
		public boolean rejectIdentity = true;
		public float identityTolerance = 5.0f;
		
		/**
		 * Maximal number of consecutive sections to be tested for an alignment model
		 */
		public int maxNumNeighbors = 10;
		
		/**
		 * Maximal number of consecutive slices for which no model could be found
		 */
		public int maxNumFailures = 3;
		
		public float layerScale = 0.1f;
		public float minR = 0.8f;
		public float maxCurvatureR = 3f;
		public float rodR = 0.8f;
		
		public int modelIndexOptimize = 1;
		public int maxIterationsOptimize = 1000;
		public int maxPlateauwidthOptimize = 200;
		
		public int resolutionSpringMesh = 16;
		public float stiffnessSpringMesh = 0.1f;
		public float dampSpringMesh = 0.6f;
		public float maxStretchSpringMesh = 2000.0f;
		public int maxIterationsSpringMesh = 1000;
		public int maxPlateauwidthSpringMesh = 200;
		
		public boolean visualize = false;
		

		public int maxNumThreads = Runtime.getRuntime().availableProcessors();
		
		public boolean setup()
		{
			/* SIFT */
			final GenericDialog gd = new GenericDialog( "Elastically align layers: SIFT parameters" );
			
			SIFT.addFields( gd, ppm.sift );
			
			gd.addNumericField( "closest/next_closest_ratio :", ppm.rod, 2 );
			
			gd.addMessage( "Geometric Consensus Filter:" );
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
			gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
			gd.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ modelIndex ] );
			gd.addCheckbox( "ignore constant background", rejectIdentity );
			gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
			
			gd.addMessage( "Matching:" );
			gd.addNumericField( "test_maximally :", maxNumNeighbors, 0, 6, "layers" );
			gd.addNumericField( "give_up_after :", maxNumFailures, 0, 6, "failures" );
			
			gd.addMessage( "Caching:" );
			gd.addCheckbox( "clear_cache", ppm.clearCache );
			
			gd.showDialog();
			
			if ( gd.wasCanceled() )
				return false;
			
			SIFT.readFields( gd, ppm.sift );
			
			ppm.rod = ( float )gd.getNextNumber();
			
			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			minNumInliers = ( int )gd.getNextNumber();
			modelIndex = gd.getNextChoiceIndex();
			rejectIdentity = gd.getNextBoolean();
			identityTolerance = ( float )gd.getNextNumber();
			maxNumNeighbors = ( int )gd.getNextNumber();
			maxNumFailures = ( int )gd.getNextNumber();
			
			ppm.clearCache = gd.getNextBoolean();
			
			
			/* Block Matching */
			final GenericDialog gdBlockMatching = new GenericDialog( "Elastically align layers: Block Matching parameters" );
			gdBlockMatching.addMessage( "Block Matching:" );
			
			/* TODO suggest isotropic resolution for this parameter */
			gdBlockMatching.addNumericField( "layer_scale :", layerScale, 2 );
			gdBlockMatching.addNumericField( "minimal_PMCC_r :", minR, 2 );
			gdBlockMatching.addNumericField( "maximal_curvature_ratio :", maxCurvatureR, 2 );
			gdBlockMatching.addNumericField( "maximal_second_best_r/best_r :", rodR, 2 );
			
			/* TODO suggest a resolution that matches maxEpsilon */
			gdBlockMatching.addNumericField( "resolution :", resolutionSpringMesh, 0 );
			
			gdBlockMatching.showDialog();
			
			if ( gdBlockMatching.wasCanceled() )
				return false;
			
			layerScale = ( float )gdBlockMatching.getNextNumber();
			minR = ( float )gdBlockMatching.getNextNumber();
			maxCurvatureR = ( float )gdBlockMatching.getNextNumber();
			rodR = ( float )gdBlockMatching.getNextNumber();
			resolutionSpringMesh = ( int )gdBlockMatching.getNextNumber();
			
			/* Optimization */
			final GenericDialog gdOptimize = new GenericDialog( "Elastically align layers: Optimization" );
			
			gdOptimize.addMessage( "Approximate Optimizer:" );
			gdOptimize.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ modelIndexOptimize ] );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsOptimize, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthOptimize, 0 );
			
			gdOptimize.addMessage( "Spring Mesh:" );
			gdOptimize.addNumericField( "stiffness :", stiffnessSpringMesh, 2 );
			gdOptimize.addNumericField( "maximal_stretch :", maxStretchSpringMesh, 2, 6, "px" );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsSpringMesh, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthSpringMesh, 0 );
			
			gdOptimize.showDialog();
			
			if ( gdOptimize.wasCanceled() )
				return false;
			
			modelIndexOptimize = gdOptimize.getNextChoiceIndex();
			maxIterationsOptimize = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthOptimize = ( int )gdOptimize.getNextNumber();
			
			stiffnessSpringMesh = ( float )gdOptimize.getNextNumber();
			maxStretchSpringMesh = ( float )gdOptimize.getNextNumber();
			maxIterationsSpringMesh = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthSpringMesh = ( int )gdOptimize.getNextNumber();
			
			return true;
		}
	}
	
	final static Param p = new Param();
	
	final static protected String layerName( final Layer layer )
	{
		return new StringBuffer( "layer z=" )
			.append( String.format( "%.3f", layer.getZ() ) )
			.append( " `" )
			.append( layer.getTitle() )
			.append( "'" )
			.toString();
		
	}
	
	final static List< Patch > filterPatches( final Layer layer, final Filter< Patch > filter )
	{
		final List< Patch > patches = layer.getAll( Patch.class );
		if ( filter != null )
		{
			for ( final Iterator< Patch > it = patches.iterator(); it.hasNext(); )
			{
				if ( !filter.accept( it.next() ) )
					it.remove();
			}
		}
		return patches;
	}
	
	
	/**
	 * Extract SIFT features and save them into the project folder.
	 * 
	 * @param layerSet the layerSet that contains all layers
	 * @param layerRange the list of layers to be aligned
	 * @param box a rectangular region of interest that will be used for alignment
	 * @param scale scale factor <= 1.0
	 * @param filter a name based filter for Patches (can be null)
	 * @param p SIFT extraction parameters
	 * @throws Exception
	 */
	final static protected void extractAndSaveLayerFeatures(
			final LayerSet layerSet,
			final List< Layer > layerRange,
			final Rectangle box,
			final float scale,
			final Filter< Patch > filter,
			final FloatArray2DSIFT.Param siftParam,
			final boolean clearCache ) throws Exception
	{
		final ExecutorService exec = Executors.newFixedThreadPool( p.maxNumThreads );
		
		/* extract features for all slices and store them to disk */
		final AtomicInteger counter = new AtomicInteger( 0 );
		final ArrayList< Future< ArrayList< Feature > > > siftTasks = new ArrayList< Future< ArrayList< Feature > > >();
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final int layerIndex = i;
			final Rectangle finalBox = box;
			siftTasks.add(
					exec.submit( new Callable< ArrayList< Feature > >()
					{
						public ArrayList< Feature > call()
						{
							final Layer layer = layerRange.get( layerIndex );
							
							final String layerName = layerName( layer );
							
							IJ.showProgress( counter.getAndIncrement(), layerRange.size() - 1 );
							
							final List< Patch > patches = filterPatches( layer, filter );
							
							ArrayList< Feature > fs = null;
							if ( !clearCache )
								fs = mpicbg.trakem2.align.Util.deserializeFeatures( layerSet.getProject(), siftParam, "layer", layer.getId() );
							
							if ( null == fs )
							{
								
								final FloatArray2DSIFT sift = new FloatArray2DSIFT( siftParam );
								final SIFT ijSIFT = new SIFT( sift );
								fs = new ArrayList< Feature >();
								final ImageProcessor ip = layer.getProject().getLoader().getFlatImage( layer, finalBox, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches, true ).getProcessor();
								ijSIFT.extractFeatures( ip, fs );
								Utils.log( fs.size() + " features extracted for " + layerName );
								
								if ( !mpicbg.trakem2.align.Util.serializeFeatures( layerSet.getProject(), p.ppm.sift, "layer", layer.getId(), fs ) )
									Utils.log( "FAILED to store serialized features for " + layerName );
							}
							else
								Utils.log( fs.size() + " features loaded for " + layerName );
							
							return fs;
						}
					} ) );
		}
		
		/* join */
		for ( Future< ArrayList< Feature > > fu : siftTasks )
			fu.get();
		
		siftTasks.clear();
		exec.shutdown();
	}
	
	

	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param first
	 * @param last
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int first,
			final int last,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		if ( !p.setup() ) return;
		
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		
		Utils.log( layerRange.size() + "" );
		
		Rectangle box = null;
		for ( Iterator< Layer > it = layerRange.iterator(); it.hasNext(); )
		{
			/* remove empty layers */
			final Layer la = it.next();
			if ( !la.contains( Patch.class, true ) )
			{
				it.remove();
				continue;
			}
			
			/* accumulate boxes */
			if ( null == box ) // The first layer:
				box = la.getMinimalBoundingBox( Patch.class, true );
			else
				box = box.union( la.getMinimalBoundingBox( Patch.class, true ) );
		}
		
		if ( fov != null )
			box = box.intersection( fov );
		
		if ( box.width <= 0 || box.height <= 0 )
		{
			Utils.log( "Bounding box empty." );
			return;
		}
		
		if ( layerRange.size() == 0 )
		{
			Utils.log( "All layers in range are empty!" );
			return;
		}
		
		/* do not work if there is only one layer selected */
		if ( layerRange.size() < 2 )
		{
			Utils.log( "All except one layer in range are empty!" );
			return;
		}

		final float scale = Math.min(  1.0f, Math.min( ( float )p.ppm.sift.maxOctaveSize / ( float )box.width, ( float )p.ppm.sift.maxOctaveSize / ( float )box.height ) );
		
		/* extract and save features, overwrite cached files if requested */
		extractAndSaveLayerFeatures( layerSet, layerRange, box, scale, filter, p.ppm.sift, p.ppm.clearCache );
		
		/* create tiles and models for all layers */
		final ArrayList< Tile< ? > > tiles = new ArrayList< Tile< ? > >();
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			switch ( p.modelIndexOptimize )
			{
			case 0:
				tiles.add( new Tile< TranslationModel2D >( new TranslationModel2D() ) );
				break;
			case 1:
				tiles.add( new Tile< RigidModel2D >( new RigidModel2D() ) );
				break;
			case 2:
				tiles.add( new Tile< SimilarityModel2D >( new SimilarityModel2D() ) );
				break;
			case 3:
				tiles.add( new Tile< AffineModel2D >( new AffineModel2D() ) );
				break;
			case 4:
				tiles.add( new Tile< HomographyModel2D >( new HomographyModel2D() ) );
				break;
			default:
				return;
			}
		}
		
		/* collect all pairs of slices for which a model could be found */
		final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > pairs = new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >();
		
		int numFailures = 0;
		
		final float pointMatchScale = p.layerScale / scale;
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final ArrayList< Thread > threads = new ArrayList< Thread >( p.maxNumThreads );
			
			final int sliceA = i;
			final Layer layerA = layerRange.get( i );
			final int range = Math.min( layerRange.size(), i + p.maxNumNeighbors + 1 );
			
			final String layerNameA = layerName( layerA );
			
J:			for ( int j = i + 1; j < range; )
			{
				final int numThreads = Math.min( p.maxNumThreads, range - j );
				final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > models =
					new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >( numThreads );
				
				for ( int k = 0; k < numThreads; ++k )
					models.add( null );
				
				for ( int t = 0;  t < numThreads && j < range; ++t, ++j )
				{
					final int ti = t;
					final int sliceB = j;
					final Layer layerB = layerRange.get( j );
					
					final String layerNameB = layerName( layerB );
					
					final Thread thread = new Thread()
					{
						public void run()
						{
							IJ.showProgress( sliceA, layerRange.size() - 1 );
							
							Utils.log( "matching " + layerNameB + " -> " + layerNameA + "..." );
							
							ArrayList< PointMatch > candidates = null;
							if ( !p.ppm.clearCache )
								candidates = mpicbg.trakem2.align.Util.deserializePointMatches(
										layerSet.getProject(), p.ppm, "layer", layerB.getId(), layerA.getId() );
							
							if ( null == candidates )
							{
								ArrayList< Feature > fs1 = mpicbg.trakem2.align.Util.deserializeFeatures(
										layerSet.getProject(), p.ppm.sift, "layer", layerA.getId() );
								ArrayList< Feature > fs2 = mpicbg.trakem2.align.Util.deserializeFeatures(
										layerSet.getProject(), p.ppm.sift, "layer", layerB.getId() );
								candidates = new ArrayList< PointMatch >( FloatArray2DSIFT.createMatches( fs2, fs1, p.ppm.rod ) );
								
								/* scale the candidates */
								for ( final PointMatch pm : candidates )
								{
									final Point p1 = pm.getP1();
									final Point p2 = pm.getP2();
									final float[] l1 = p1.getL();
									final float[] w1 = p1.getW();
									final float[] l2 = p2.getL();
									final float[] w2 = p2.getW();
									
									l1[ 0 ] *= pointMatchScale;
									l1[ 1 ] *= pointMatchScale;
									w1[ 0 ] *= pointMatchScale;
									w1[ 1 ] *= pointMatchScale;
									l2[ 0 ] *= pointMatchScale;
									l2[ 1 ] *= pointMatchScale;
									w2[ 0 ] *= pointMatchScale;
									w2[ 1 ] *= pointMatchScale;
									
								}
								
								if ( !mpicbg.trakem2.align.Util.serializePointMatches(
										layerSet.getProject(), p.ppm, "layer", layerB.getId(), layerA.getId(), candidates ) )
									Utils.log( "Could not store point match candidates for layers " + layerNameB + " and " + layerNameA + "." );
							}
		
							AbstractModel< ? > model;
							switch ( p.modelIndex )
							{
							case 0:
								model = new TranslationModel2D();
								break;
							case 1:
								model = new RigidModel2D();
								break;
							case 2:
								model = new SimilarityModel2D();
								break;
							case 3:
								model = new AffineModel2D();
								break;
							case 4:
								model = new HomographyModel2D();
								break;
							default:
								return;
							}
							
							final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
							
							boolean modelFound;
							boolean again = false;
							try
							{
								do
								{
									again = false;
									modelFound = model.filterRansac(
												candidates,
												inliers,
												1000,
												p.maxEpsilon * p.layerScale,
												p.minInlierRatio,
												p.minNumInliers,
												3 );
									if ( modelFound && p.rejectIdentity )
									{
										final ArrayList< Point > points = new ArrayList< Point >();
										PointMatch.sourcePoints( inliers, points );
										if ( Transforms.isIdentity( model, points, p.identityTolerance *  p.layerScale ) )
										{
											IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
											candidates.removeAll( inliers );
											inliers.clear();
											again = true;
										}
									}
								}
								while ( again );
							}
							catch ( NotEnoughDataPointsException e )
							{
								modelFound = false;
							}
							
							if ( modelFound )
							{
								Utils.log( layerNameB + " -> " + layerNameA + ": " + inliers.size() + " corresponding features with an average displacement of " + ( PointMatch.meanDistance( inliers ) / p.layerScale ) + "px identified." );
								Utils.log( "Estimated transformation model: " + model );
								models.set( ti, new Triple< Integer, Integer, AbstractModel< ? > >( sliceA, sliceB, model ) );
							}
							else
							{
								Utils.log( layerNameB + " -> " + layerNameA + ": no correspondences found." );
								return;
							}
						}
					};
					threads.add( thread );
					thread.start();
				}
				
				for ( final Thread thread : threads )
					thread.join();
				
				threads.clear();
				
				/* collect successfully matches pairs and break the search on gaps */
				for ( int t = 0; t < models.size(); ++t )
				{
					final Triple< Integer, Integer, AbstractModel< ? > > pair = models.get( t );
					if ( pair == null )
					{
						if ( ++numFailures > p.maxNumFailures )
							break J;
					}
					else
					{
						numFailures = 0;
						pairs.add( pair );
					}
				}
			}
		}
		
		/* Elastic alignment */
		
		/* Initialization */
		final TileConfiguration initMeshes = new TileConfiguration();
		
		final int meshWidth = ( int )Math.ceil( box.width * p.layerScale );
		final int meshHeight = ( int )Math.ceil( box.height * p.layerScale );
		
		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( layerRange.size() );
		for ( int i = 0; i < layerRange.size(); ++i )
			meshes.add(
					new SpringMesh(
							p.resolutionSpringMesh,
							meshWidth,
							meshHeight,
							p.stiffnessSpringMesh,
							p.maxStretchSpringMesh * p.layerScale,
							p.dampSpringMesh ) );
		
		final int blockRadius = Math.max( 32, meshWidth / p.resolutionSpringMesh / 2 );
		
		/** TODO set this something more than the largest error by the approximate model */
		final int searchRadius = ( int )Math.round( p.layerScale * p.maxEpsilon );
		
		for ( final Triple< Integer, Integer, AbstractModel< ? > > pair : pairs )
		{
			final SpringMesh m1 = meshes.get( pair.a );
			final SpringMesh m2 = meshes.get( pair.b );

			ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
			ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

			final Collection< Vertex > v1 = m1.getVertices();
			final Collection< Vertex > v2 = m2.getVertices();
			
			final Layer layer1 =  layerRange.get( pair.a );
			final Layer layer2 =  layerRange.get( pair.b );
			
			final Image img1 = layerSet.getProject().getLoader().getFlatAWTImage(
					layer1,
					box,
					p.layerScale,
					0xffffffff,
					ImagePlus.COLOR_RGB,
					Patch.class,
					filterPatches( layer1, filter ),
					true,
					new Color( 0x00ffffff, true ) );
			
			final Image img2 = layerSet.getProject().getLoader().getFlatAWTImage(
					layer2,
					box,
					p.layerScale,
					0xffffffff,
					ImagePlus.COLOR_RGB,
					Patch.class,
					filterPatches( layer2, filter ),
					true,
					new Color( 0x00ffffff, true ) );
			
			final int width = img1.getWidth( null );
			final int height = img1.getHeight( null );

			final FloatProcessor ip1 = new FloatProcessor( width, height );
			final FloatProcessor ip2 = new FloatProcessor( width, height );
			final FloatProcessor ip1Mask = new FloatProcessor( width, height );
			final FloatProcessor ip2Mask = new FloatProcessor( width, height );
			
			mpicbg.trakem2.align.Util.imageToFloatAndMask( img1, ip1, ip1Mask );
			mpicbg.trakem2.align.Util.imageToFloatAndMask( img2, ip2, ip2Mask );
			
			BlockMatching.matchByMaximalPMCC(
					ip1,
					ip2,
					ip1Mask,
					ip2Mask,
					1.0f,
					( ( InvertibleCoordinateTransform )pair.c ).createInverse(),
					blockRadius,
					blockRadius,
					searchRadius,
					searchRadius,
					p.minR,
					p.rodR,
					p.maxCurvatureR,
					v1,
					pm12,
					new ErrorStatistic( 1 ) );

			IJ.log( pair.a + " > " + pair.b + ": found " + pm12.size() + " correspondences." );

			/* <visualisation> */
			//			final List< Point > s1 = new ArrayList< Point >();
			//			PointMatch.sourcePoints( pm12, s1 );
			//			final ImagePlus imp1 = new ImagePlus( i + " >", ip1 );
			//			imp1.show();
			//			imp1.setOverlay( BlockMatching.illustrateMatches( pm12 ), Color.yellow, null );
			//			imp1.setRoi( Util.pointsToPointRoi( s1 ) );
			//			imp1.updateAndDraw();
			/* </visualisation> */

			BlockMatching.matchByMaximalPMCC(
					ip2,
					ip1,
					ip2Mask,
					ip1Mask,
					1.0f,
					pair.c,
					blockRadius,
					blockRadius,
					searchRadius,
					searchRadius,
					p.minR,
					p.rodR,
					p.maxCurvatureR,
					v2,
					pm21,
					new ErrorStatistic( 1 ) );

			IJ.log( pair.a + " < " + pair.b + ": found " + pm21.size() + " correspondences." );
					
			/* <visualisation> */
			//			final List< Point > s2 = new ArrayList< Point >();
			//			PointMatch.sourcePoints( pm21, s2 );
			//			final ImagePlus imp2 = new ImagePlus( i + " <", ip2 );
			//			imp2.show();
			//			imp2.setOverlay( BlockMatching.illustrateMatches( pm21 ), Color.yellow, null );
			//			imp2.setRoi( Util.pointsToPointRoi( s2 ) );
			//			imp2.updateAndDraw();
			/* </visualisation> */
			
			final float springConstant  = 1.0f / ( pair.b - pair.a );
			IJ.log( pair.a + " <> " + pair.b + " spring constant = " + springConstant );
	
			for ( final PointMatch pm : pm12 )
			{
				final Vertex p1 = ( Vertex )pm.getP1();
				final Vertex p2 = new Vertex( pm.getP2() );
				p1.addSpring( p2, new Spring( 0, springConstant ) );
				m2.addPassiveVertex( p2 );
			}
		
			for ( final PointMatch pm : pm21 )
			{
				final Vertex p1 = ( Vertex )pm.getP1();
				final Vertex p2 = new Vertex( pm.getP2() );
				p1.addSpring( p2, new Spring( 0, springConstant ) );
				m1.addPassiveVertex( p2 );
			}
			
			final Tile< ? > t1 = tiles.get( pair.a );
			final Tile< ? > t2 = tiles.get( pair.b );
			
			/*
			 * adding Tiles to the initialing TileConfiguration, adding a Tile
			 * multiple times does not harm because the TileConfiguration is
			 * backed by a Set. 
			 */
			if ( pm12.size() > pair.c.getMinNumMatches() )
			{
				initMeshes.addTile( t1 );
				initMeshes.addTile( t2 );
				t1.connect( t2, pm12 );
			}
			if ( pm21.size() > pair.c.getMinNumMatches() )
			{
				initMeshes.addTile( t1 );
				initMeshes.addTile( t2 );
				t2.connect( t1, pm21 );
			}
		}
		
		/* pre-align by optimizing a piecewise linear model */ 
		initMeshes.optimize(
				p.maxEpsilon * p.layerScale,
				p.maxIterationsSpringMesh,
				p.maxPlateauwidthSpringMesh );
		for ( int i = 0; i < layerRange.size(); ++i )
			meshes.get( i ).init( tiles.get( i ).getModel() );

		/* optimize the meshes */
		try
		{
			long t0 = System.currentTimeMillis();
			IJ.log("Optimizing spring meshes...");
			
			SpringMesh.optimizeMeshes(
					meshes,
					p.maxEpsilon * p.layerScale,
					p.maxIterationsSpringMesh,
					p.maxPlateauwidthSpringMesh,
					p.visualize );

			IJ.log("Done optimizing spring meshes. Took " + (System.currentTimeMillis() - t0) + " ms");
			
		}
		catch ( NotEnoughDataPointsException e ) { e.printStackTrace(); }
		
		/* translate relative to bounding box */
		for ( final SpringMesh mesh : meshes )
		{
			for ( final PointMatch pm : mesh.getVA().keySet() )
			{
				final Point p1 = pm.getP1();
				final Point p2 = pm.getP2();
				final float[] l = p1.getL();
				final float[] w = p2.getW();
				l[ 0 ] = l[ 0 ] / p.layerScale + box.x;
				l[ 1 ] = l[ 1 ] / p.layerScale + box.y;
				w[ 0 ] = w[ 0 ] / p.layerScale + box.x;
				w[ 1 ] = w[ 1 ] / p.layerScale + box.y;
			}
		}
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final Layer layer = layerRange.get( i );
			
			final MovingLeastSquaresTransform mlt = new MovingLeastSquaresTransform();
			mlt.setModel( AffineModel2D.class );
			mlt.setAlpha( 2.0f );
			mlt.setMatches( meshes.get( i ).getVA().keySet() );
			
			for ( final Patch patch : filterPatches( layer, filter ) )
			{
				mpicbg.trakem2.align.Util.applyLayerTransformToPatch( patch, mlt );
			}
		}
		
		Utils.log( "Done." );
	}
}
