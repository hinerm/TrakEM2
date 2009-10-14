/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;


import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.ImagePlus;
import ij.ImageStack;

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.imaging.LayerStack;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.Thing;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.Collection;


/** A LayerSet represents an axis on which layers can be stacked up. Paints with 0.67 alpha transparency when not active. */
public final class LayerSet extends Displayable implements Bucketable { // Displayable is already extending DBObject

	// the anchors for resizing
	static public final int NORTH = 0;
	static public final int NORTHEAST = 1;
	static public final int EAST = 2;
	static public final int SOUTHEAST = 3;
	static public final int SOUTH = 4;
	static public final int SOUTHWEST = 5;
	static public final int WEST = 6;
	static public final int NORTHWEST = 7;
	static public final int CENTER = 8;

	// the possible rotations
	static public final int R90 = 9;
	static public final int R270 = 10;
	// the posible flips
	static public final int FLIP_HORIZONTAL = 11;
	static public final int FLIP_VERTICAL = 12;

	// postions in the stack
	static public final int TOP = 13;
	static public final int UP = 14;
	static public final int DOWN = 15;
	static public final int BOTTOM = 16;

	static public final String[] snapshot_modes = new String[]{"Full","Outlines","Disabled"};

	/** 0, 1, 2 -- corresponding to snapshot_modes entries above. */
	private int snapshots_mode = 0;

	static public final String[] ANCHORS =  new String[]{"north", "north east", "east", "southeast", "south", "south west", "west", "north west", "center"};
	static public final String[] ROTATIONS = new String[]{"90 right", "90 left", "Flip horizontally", "Flip vertically"};

	private double layer_width; // the Displayable.width is for the representation, not for the dimensions of the LayerSet!
	private double layer_height;
	private double rot_x;
	private double rot_y;
	private double rot_z; // should be equivalent to the Displayable.rot
	private final ArrayList<Layer> al_layers = new ArrayList<Layer>();
	/** The layer in which this LayerSet lives. If null, this is the root LayerSet. */
	private Layer parent = null;
	/** A LayerSet can contain Displayables that are show in every single Layer, such as Pipe objects. */
	private final ArrayList<ZDisplayable> al_zdispl = new ArrayList<ZDisplayable>();

	/** For creating snapshots. */
	private boolean snapshots_quality = true;

	/** Tool to manually register using landmarks across two layers. Uses the toolbar's 'Align tool'. */
	private Align align = null;

	/** The scaling applied to the Layers when painting them for presentation as a LayerStack. If -1, automatic mode (default) */
	private double virtual_scale = -1;
	/** The maximum size of either width or height when virtuzaling pixel access to the layers.*/
	private int max_dimension = 1024;
	private boolean virtualization_enabled = false;

	private Calibration calibration = new Calibration(); // default values

	/** Dummy. */
	protected LayerSet(Project project, long id) {
		super(project, id, null, false, null, 20, 20);
	}

	/** Create a new LayerSet with a 0,0,0 rotation vector and default 20,20 px Displayable width,height. */
	public LayerSet(Project project, String title, double x, double y, Layer parent, double layer_width, double layer_height) {
		super(project, title, x, y);
		rot_x = rot_y = rot_z = 0.0D;
		this.width = 20;
		this.height = 20; // for the label that paints into the parent Layer
		this.parent = parent;
		this.layer_width = layer_width;
		this.layer_height = layer_height;
		addToDatabase();
	}

	/** Reconstruct from the database. */
	public LayerSet(Project project, long id, String title, double width, double height, double rot_x, double rot_y, double rot_z, double layer_width, double layer_height, boolean locked, int shapshots_mode, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.rot_x = rot_x;
		this.rot_y = rot_y;
		this.rot_z = rot_z;
		this.layer_width = layer_width;
		this.layer_height= layer_height;
		this.snapshots_mode = snapshots_mode;
		// the parent will be set by the LayerThing.setup() calling Layer.addSilently()
		// the al_layers will be filled idem.
	}

	/** Reconstruct from an XML entry. */
	public LayerSet(Project project, long id, HashMap ht_attributes, HashMap ht_links) {
		super(project, id, ht_attributes, ht_links);
		for (Iterator it = ht_attributes.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String data = (String)entry.getValue();
			if (key.equals("layer_width")) {
				this.layer_width = Double.parseDouble(data);
			} else if (key.equals("layer_height")) {
				this.layer_height = Double.parseDouble(data);
			} else if (key.equals("rot_x")) {
				this.rot_x = Double.parseDouble(data);
			} else if (key.equals("rot_y")) {
				this.rot_y = Double.parseDouble(data);
			} else if (key.equals("rot_z")) {
				this.rot_z = Double.parseDouble(data);
			} else if (key.equals("snapshots_quality")) {
				snapshots_quality = Boolean.valueOf(data.trim().toLowerCase());
			} else if (key.equals("snapshots_mode")) {
				String smode = data.trim();
				for (int i=0; i<snapshot_modes.length; i++) {
					if (smode.equals(snapshot_modes[i])) {
						snapshots_mode = i;
						break;
					}
				}
			}
			// the above would be trivial in Jython, and can be done by reflection! The problem would be in the parsing, that would need yet another if/else if/ sequence was any field to change or be added.
		}
	}

	/** For reconstruction purposes: set the active layer to the ZDisplayable objects. Recurses through LayerSets in the children layers. */
	public void setup() {
		final Layer la0 = al_layers.get(0);
		for (ZDisplayable zd : al_zdispl) zd.setLayer(la0); // just any Layer
		for (Layer layer : al_layers) {
			for (Iterator itl = layer.getDisplayables().iterator(); itl.hasNext(); ) {
				Object ob = itl.next();
				if (ob instanceof LayerSet) {
					((LayerSet)ob).setup();
				}
			}
		}
	}

	/** Create a new LayerSet in the middle of the parent Layer. */
	public LayerSet create(Layer parent_layer) {
		if (null == parent_layer) return null;
		GenericDialog gd = ControlWindow.makeGenericDialog("New Layer Set");
		gd.addMessage("In pixels:");
		gd.addNumericField("width: ", this.layer_width, 3);
		gd.addNumericField("height: ", this.layer_height, 3);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		try {
			double width = gd.getNextNumber();
			double height = gd.getNextNumber();
			if (Double.isNaN(width) || Double.isNaN(height)) return null;
			if (0 == width || 0 == height) {
				Utils.showMessage("Cannot accept zero width or height for LayerSet dimensions.");
				return null;
			}
			// make a new LayerSet with x,y in the middle of the parent_layer
			return new LayerSet(project, "Layer Set", parent_layer.getParent().getLayerWidth() / 2, parent_layer.getParent().getLayerHeight() / 2, parent_layer, width/2, height/2);
		} catch (Exception e) { Utils.log("LayerSet.create: " + e); }
		return null;
	}

	/** Add a new Layer silently, ordering by z as well.*/
	public void addSilently(final Layer layer) {
		if (null == layer || al_layers.contains(layer)) return;
		try {
			double z = layer.getZ();
			int i = 0;
			for (Layer la : al_layers) {
				if (! (la.getZ() < z) ) {
					al_layers.add(i, layer);
					layer.setParentSilently(this);
					return;
				}
				i++;
			}
			// else, add at the end
			al_layers.add(layer);
			layer.setParentSilently(this);
		} catch (Exception e) {
			Utils.log("LayerSet.addSilently: Not a Layer, not adding DBObject id=" + layer.getId());
			return;
		}
	}

	/** Add a new Layer, inserted according to its Z. */
	public void add(final Layer layer) {
		if (-1 != al_layers.indexOf(layer)) return;
		final double z = layer.getZ();
		final int n = al_layers.size();
		int i = 0;
		for (; i<n; i++) {
			Layer l = (Layer)al_layers.get(i);
			if (l.getZ() < z) continue;
			break;
		}
		if (i < n) {
			al_layers.add(i, layer);
		} else {
			al_layers.add(layer);
		}
		layer.setParent(this);
		Display.updateLayerScroller(this);
		//debug();
	}

	private void debug() {
		Utils.log("LayerSet debug:");
		for (int i=0; i<al_layers.size(); i++)
			Utils.log(i + " : " + ((Layer)al_layers.get(i)).getZ());
	}

	public Layer getParent() {
		return parent;
	}

	/** 'update' in database or not. */
	public void setLayer(Layer layer, boolean update) {
		super.setLayer(layer, update);
		if (null != layer) this.parent = layer; // repeated pointer, eliminate 'parent' !
	}

	public void setParent(Layer layer) {
		if (null == layer || layer == parent) return;
		this.parent = layer;
		updateInDatabase("parent_id");
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		if (ProjectToolbar.SELECT != ProjectToolbar.getToolId()) return;
		Display.setActive(me, this);
		if (2 == me.getClickCount() && al_layers.size() > 0) {
			new Display(project, al_layers.get(0));
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
		if (ProjectToolbar.SELECT != ProjectToolbar.getToolId()) return;
		super.translate(x_d - x_d_old, y_d - y_d_old);
		Display.repaint(layer, this, 0);
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		// nothing
	}

	public void keyPressed(KeyEvent ke) {
		Utils.log("LayerSet.keyPressed: not yet implemented.");
		// TODO
	}

	public String toString() {
		return this.title;
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		//set color
		g.setColor(this.color);
		// fill a background box
		g.fillRect(0, 0, (int)(this.width), (int)(this.height));
		g.setColor(new Color(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue()).brighter()); // the "opposite", but brighter, so it won't fail to generate contrast if the color is 127 in all channels
		int x = (int)(this.width/5);
		int y = (int)(this.height/5);
		int width = (int)(this.width/5);
		int height = (int)(this.height/5 * 3);

		g.fillRect(x, y, width, height);

		x = (int)(this.width/5 * 2);
		y = (int)(this.height/5 * 3);
		width = (int)(this.width/5 * 2);
		height = (int)(this.height/5);

		g.fillRect(x, y, width, height);

		//Transparency: fix composite back to original.
		if (alpha != 1.0f) {
			g.setComposite(original_composite);
		}
	}

	public double getLayerWidth() { return layer_width; }
	public double getLayerHeight() { return layer_height; }
	public double getRotX() { return rot_x; }
	public double getRotY() { return rot_y; }
	public double getRotZ() { return rot_z; }

	public int size() {
		return al_layers.size();
	}

	public void setRotVector(double rot_x, double rot_y, double rot_z) {
		if (Double.isNaN(rot_x) || Double.isNaN(rot_y) || Double.isNaN(rot_z)) {
			Utils.showMessage("LayerSet: Rotation vector contains NaNs. Not updating.");
			return;
		} else if (rot_x == this.rot_x && rot_y == this.rot_y && rot_z == this.rot_z) {
			return;
		}
		this.rot_x = rot_x;
		this.rot_y = rot_y;
		this.rot_z = rot_z;
		updateInDatabase("rot");
	}

	/** Used by the Loader after loading blindly a lot of Patches. Will crop the canvas to the minimum size possible. */
	public boolean setMinimumDimensions() {
		// find current x,y,width,height that crops the canvas without cropping away any Displayable
		double x = Double.NaN;
		double y = Double.NaN;
		double xe = 0; // lower right corner (x end)
		double ye = 0;
		double tx = 0;
		double ty = 0;
		double txe = 0;
		double tye = 0;
		// collect all Displayable and ZDisplayable objects
		final ArrayList al = new ArrayList();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(((Layer)al_layers.get(i)).getDisplayables());
		}
		al.addAll(al_zdispl);

		// find minimum bounding box
		Rectangle b = new Rectangle();
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			b = d.getBoundingBox(b); // considers rotation
			tx = b.x;//d.getX();
			ty = b.y;//d.getY();
			// set first coordinates
			if (Double.isNaN(x) || Double.isNaN(y)) { // Double.NaN == x fails!
				x = tx;
				y = ty;
			}
			txe = tx + b.width;//d.getWidth();
			tye = ty + b.height;//d.getHeight();
			if (tx < x) x = tx;
			if (ty < y) y = ty;
			if (txe > xe) xe = txe;
			if (tye > ye) ye = tye;
		}
		// if none, then stop
		if (Double.isNaN(x) || Double.isNaN(y)) {
			Utils.showMessage("No displayable objects, don't know how to resize the canvas and Layerset.");
			return false;
		}

		double w = xe - x;
		double h = ye - y;
		if (w <= 0 || h <= 0) {
			Utils.log("LayerSet.setMinimumDimensions: zero width or height, NOT resizing.");
			return false;
		}

		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

		// translate
		if (0 != x || 0 != y) {
			project.getLoader().startLargeUpdate();
			try {
				final AffineTransform at2 = new AffineTransform();
				at2.translate(-x, -y);
				//Utils.log2("translating all displayables by " + x + "," + y);
				for (Iterator it = al.iterator(); it.hasNext(); ) {
					//((Displayable)it.next()).translate(-x, -y, false); // drag regardless of getting off current LayerSet bounds
					// optimized to avoid creating so many AffineTransform instances:
					final Displayable d = (Displayable)it.next();
					//Utils.log2("BEFORE: " + d.getBoundingBox());
					d.getAffineTransform().preConcatenate(at2);
					//Utils.log2("AFTER: " + d.getBoundingBox());
					d.updateInDatabase("transform");
				}
				project.getLoader().commitLargeUpdate();
			} catch (Exception e) {
				IJError.print(e);
				project.getLoader().rollback();
				return false;
			}
		}

		//Utils.log("x,y  xe,ye : " + x + "," + y + "  " + xe + "," + ye);
		// finally, accept:
		if (w != layer_width || h != layer_height) {
			this.layer_width = Math.ceil(w); // stupid int to double conversions ... why floating point math is a non-solved problem? Well, it is for SBCL
			this.layer_height = Math.ceil(h);
			updateInDatabase("layer_dimensions");
			if (null != root) recreateBuckets(true);
			// and notify the Displays, if any
			Display.update(this);
			Display.pack(this);
		}

		// Record current state:
		addEditStep(new LayerSet.DoResizeLayerSet(this));

		return true;
	}

	/** Enlarges the display in the given direction; the anchor is the point to keep still, and can be any of LayerSet.NORTHWEST (top-left), etc. */
	synchronized public boolean enlargeToFit(final Displayable d, final int anchor) {
		final Rectangle r = new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
		final Rectangle b = d.getBoundingBox(null);
		// check if necessary
		if (r.contains(b)) return false;
		// else, enlarge to fit it
		r.add(b);
		return setDimensions(r.width, r.height, anchor);
	}

	/** May leave objects beyond the visible window. */
	public void setDimensions(double x, double y, double layer_width, double layer_height) {
		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

		this.layer_width = layer_width;
		this.layer_height = layer_height;
		final AffineTransform affine = new AffineTransform();
		affine.translate(-x, -y);
		for (ZDisplayable zd : al_zdispl) {
			zd.getAffineTransform().preConcatenate(affine);
			zd.updateInDatabase("transform");
		}
		for (Layer la : al_layers) la.apply(Displayable.class, affine);
		if (null != root) {
			recreateBuckets(true);
		}
		Display.update(this);

		// Record new state
		addEditStep(new LayerSet.DoResizeLayerSet(this));
	}

	/** Returns false if any Displayables are being partially or totally cropped away. */
	public boolean setDimensions(double layer_width, double layer_height, int anchor) {
		// check preconditions
		if (Double.isNaN(layer_width) || Double.isNaN(layer_height)) { Utils.log("LayerSet.setDimensions: NaNs! Not adjusting."); return false; }
		if (layer_width <=0 || layer_height <= 0) { Utils.showMessage("LayerSet: can't accept zero or a minus for layer width or height"); return false; }
		if (anchor < NORTH || anchor > CENTER) {  Utils.log("LayerSet: wrong anchor, not resizing."); return false; }

		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

		// new coordinates:
		double new_x = 0;// the x,y of the old 0,0
		double new_y = 0;
		switch (anchor) {
			case NORTH:
			case SOUTH:
			case CENTER:
				new_x = (layer_width - this.layer_width) / 2; // (this.layer_width - layer_width) / 2;
				break;
			case NORTHWEST:
			case WEST:
			case SOUTHWEST:
				new_x = 0;
				break;
			case NORTHEAST:
			case EAST:
			case SOUTHEAST:
				new_x = layer_width - this.layer_width; // (this.layer_width - layer_width);
				break;
		}
		switch (anchor) {
			case WEST:
			case EAST:
			case CENTER:
				new_y = (layer_height - this.layer_height) / 2;
				break;
			case NORTHWEST:
			case NORTH:
			case NORTHEAST:
				new_y = 0;
				break;
			case SOUTHWEST:
			case SOUTH:
			case SOUTHEAST:
				new_y = (layer_height - this.layer_height);
				break;
		}

		/*
		Utils.log("anchor: " + anchor);
		Utils.log("LayerSet: existing w,h = " + this.layer_width + "," + this.layer_height);
		Utils.log("LayerSet: new      w,h = " + layer_width + "," + layer_height);
		*/

		// collect all Displayable and ZDisplayable objects
		ArrayList al = new ArrayList();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(((Layer)al_layers.get(i)).getDisplayables());
		}
		al.addAll(al_zdispl);

		// check that no displayables are being cropped away
		if (layer_width < this.layer_width || layer_height < this.layer_height) {
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				Rectangle b = d.getBoundingBox(null);
				double dw = b.getWidth();
				double dh = b.getHeight();
				// respect 10% margins
				if (b.x + dw + new_x < 0.1 * dw || b.x + 0.9 * dw + new_x > layer_width || b.y + dh + new_y < 0.1 * dh || b.y + 0.9 * dh + new_y > layer_height) {
					// cropping!
					Utils.showMessage("Cropping " + d + "\nLayerSet: not resizing.");
					return false;
				}
			}
		}
		this.layer_width = layer_width;
		this.layer_height = layer_height;
		//Utils.log("LayerSet.setDimensions: new_x,y: " + new_x + "," + new_y);
		// translate all displayables
		if (0 != new_x || 0 != new_y) {
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				Rectangle b = d.getBoundingBox(null);
				//Utils.log("d x,y = " + b.x + ", " + b.y);
				d.setLocation(b.x + new_x, b.y + new_y);
			}
		}

		updateInDatabase("layer_dimensions");
		if (null != root) recreateBuckets(true);
		// and notify the Display
		Display.update(this);
		Display.pack(this);

		// Record new state
		addEditStep(new LayerSet.DoResizeLayerSet(this));

		return true;
	}

	protected boolean remove2(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null != al_layers && al_layers.size() > 0 ? " and all its children?" : ""))) return false;
		}
		LayerThing lt = project.findLayerThing(this);
		if (null == lt) return false;
		return project.getLayerTree().remove(check, lt, null); // will end up calling remove(boolean) on this object
	}

	public boolean remove(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null != al_layers && al_layers.size() > 0 ? " and all its children?" : ""))) return false;
		}
		// delete all layers
		while (0 != al_layers.size()) {
			if (!((DBObject)al_layers.get(0)).remove(false)) {
				Utils.showMessage("LayerSet id= " + id + " : Deletion incomplete, check database.");
				return false;
			}
		}
		// delete the ZDisplayables
		Iterator it = al_zdispl.iterator();
		while (it.hasNext()) {
			((ZDisplayable)it.next()).remove(false); // will call back the LayerSet.remove(ZDisplayable)
		}
		// remove the self
		if (null != parent) parent.remove(this);
		removeFromDatabase();
		return true;
	}

	/** Remove a child. Does not destroy it or delete it from the database. */
	public void remove(Layer layer) {
		if (null == layer || -1 == al_layers.indexOf(layer)) return;
		al_layers.remove(layer);
		Display.updateLayerScroller(this);
		Display.updateTitle(this);
	}

	public Layer next(Layer layer) {
		int i = al_layers.indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.next: no such Layer " + layer);
			return layer;
		}
		if (al_layers.size() -1 == i) return layer;
		else return (Layer)al_layers.get(i+1);
	}

	public Layer previous(Layer layer) {
		int i = al_layers.indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.previous: no such Layer " + layer);
			return layer;
		}
		if (0 == i) return layer;
		else return (Layer)al_layers.get(i-1);
	}

	public Layer nextNonEmpty(Layer layer) {
		Layer next = layer;
		Layer given = layer;
		do {
			layer = next;
			next = next(layer);
			if (!next.isEmpty()) return next;
		} while (next != layer);
		return given;
	}
	public Layer previousNonEmpty(Layer layer) {
		Layer previous = layer;
		Layer given = layer;
		do {
			layer = previous;
			previous = previous(layer);
			if (!previous.isEmpty()) return previous;
		} while (previous != layer);
		return given;
	}

	public int getLayerIndex(final long id) {
		for (int i=al_layers.size()-1; i>-1; i--) {
			if (((Layer)al_layers.get(i)).getId() == id) return i;
		}
		return -1;
	}

	/** Find a layer by index, or null if none. */
	public Layer getLayer(final int i) {
		if (i >=0 && i < al_layers.size()) return (Layer)al_layers.get(i);
		return null;
	}

	/** Find a layer with the given id, or null if none. */
	public Layer getLayer(final long id) {
		for (Layer layer : al_layers) {
			if (layer.getId() == id) return layer;
		}
		return null;
	}

	/** Returns the first layer found with the given Z coordinate, rounded to seventh decimal precision, or null if none found. */
	public Layer getLayer(final double z) {
		double error = 0.0000001; // TODO adjust to an optimal
		for (Layer layer : al_layers) {
			if (error > Math.abs(layer.getZ() - z)) { // floating-point arithmetic is still not a solved problem!
				return layer;
			}
		}
		return null;
	}

	public Layer getNearestLayer(final double z) {
		double min_dist = Double.MAX_VALUE;
		Layer closest = null;
		for (Layer layer : al_layers) {
			double dist = Math.abs(layer.getZ() - z);
			if (dist < min_dist) {
				min_dist = dist;
				closest = layer;
			}
		}
		return closest;
	}

	/** Returns null if none has the given z and thickness. If 'create' is true and no layer is found, a new one with the given Z is created and added to the LayerTree. */
	public Layer getLayer(double z, double thickness, boolean create) {
		Iterator it = al_layers.iterator();
		Layer layer = null;
		double error = 0.0000001; // TODO adjust to an optimal
		while (it.hasNext()) {
			Layer l = (Layer)it.next();
			if (error > Math.abs(l.getZ() - z) && error > Math.abs(l.getThickness() - thickness)) { // floating point is still not a solved problem.
				//Utils.log("LayerSet.getLayer: found layer with z=" + l.getZ());
				layer = l;
			}
		}
		if (create && null == layer && !Double.isNaN(z) && !Double.isNaN(thickness)) {
			//Utils.log("LayerSet.getLayer: creating new Layer with z=" + z);
			layer = new Layer(project, z, thickness, this);
			add(layer);
			project.getLayerTree().addLayer(this, layer);
		}
		return layer;
	}

	/** Add a Displayable to be painted in all Layers, such as a Pipe. Also updates open displays of the fact. */
	public void add(final ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) {
			Utils.log2("LayerSet: not adding zdispl");
			return;
		}
		al_zdispl.add(zdispl); // at the top

		zdispl.setLayerSet(this);
		// The line below can fail (and in the addSilently as well) if one can add zdispl objects while no Layer has been created. But the ProjectThing.createChild prevents this situation.
		zdispl.setLayer(al_layers.get(0));
		zdispl.updateInDatabase("layer_set_id"); // TODO: update stack index? It should!

		// insert into bucket
		if (null != root) {
			// add as last, then update
			root.put(al_zdispl.size()-1, zdispl, zdispl.getBoundingBox(null));
			//root.update(this, zdispl, 0, al_zdispl.size()-1);
		}

		Display.add(this, zdispl);
	}

	public void addAll(final Collection<? extends ZDisplayable> coll) {
		if (null == coll || 0 == coll.size()) return;
		for (final ZDisplayable zd : coll) {
			al_zdispl.add(zd);
			zd.setLayerSet(this);
			zd.setLayer(al_layers.get(0));
			zd.updateInDatabase("layer_set_id");
		}
		if (null != root) {
			recreateBuckets(false); // only ZDisplayable
		}
		Display.addAll(this, coll);
	}

	/** Used for reconstruction purposes, avoids repainting or updating. */
	public void addSilently(final ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) return;
		try {
			zdispl.setLayer(0 == al_layers.size() ? null : al_layers.get(0));
			zdispl.setLayerSet(this, false);
			//Utils.log2("setLayerSet to ZDipl id=" + zdispl.getId());
			al_zdispl.add(zdispl);
		} catch (Exception e) {
			Utils.log("LayerSet.addSilently: not adding ZDisplayable with id=" + zdispl.getId());
			IJError.print(e);
			return;
		}
	}

	/** Remove a child. Does not destroy the child nor remove it from the database, only from the Display. */
	public boolean remove(final ZDisplayable zdispl) {
		if (null == zdispl || null == al_zdispl || -1 == al_zdispl.indexOf(zdispl)) return false;
		// remove from Bucket before modifying stack index
		if (null != root) Bucket.remove(zdispl, db_map);
		// now remove proper, so stack_index hasn't changed yet
		al_zdispl.remove(zdispl);
		Display.remove(zdispl);
		return true;
	}

	public ArrayList<ZDisplayable> getZDisplayables(final Class c) {
		return getZDisplayables(c, false);
	}

	/** Returns a list of ZDisplayable of class c only.*/
	public ArrayList<ZDisplayable> getZDisplayables(final Class c, final boolean instance_of) {
		final ArrayList<ZDisplayable> al = new ArrayList<ZDisplayable>();
		if (null == c) return al;
		if (Displayable.class == c || ZDisplayable.class == c) {
			al.addAll(al_zdispl);
			return al;
		}
		if (instance_of) {
			for (ZDisplayable zd : al_zdispl) {
				if (c.isInstance(zd)) al.add(zd);
			}
		} else {
			for (ZDisplayable zd : al_zdispl) {
				if (zd.getClass() == c) al.add(zd);
			}
		}
		return al;
	}

	public ArrayList<ZDisplayable> getZDisplayables(final Class c, final Layer layer, final Area aroi, final boolean visible_only) {
		final ArrayList<ZDisplayable> al = getZDisplayables(c);
		final double z = layer.getZ();
		for (Iterator<ZDisplayable> it = al.iterator(); it.hasNext(); ) {
			ZDisplayable zd = it.next();
			if (visible_only && !zd.isVisible()) { it.remove(); continue; }
			if (!zd.intersects(aroi, z, z)) it.remove();
		}
		return al;
	}

	public boolean contains(final Layer layer) {
		if (null == layer) return false;
		return -1 != al_layers.indexOf(layer);
	}

	public boolean contains(final Displayable zdispl) {
		if (null == zdispl) return false;
		return -1 != al_zdispl.indexOf(zdispl);
	}

	/** Returns a copy of the layer list. */
	public ArrayList<Layer> getLayers() {
		return (ArrayList<Layer>)al_layers.clone(); // for integrity and safety, return a copy.
	}

	public boolean isDeletable() {
		return false;
	}

	/** Overiding. The alpha is used to show whether the LayerSet object is selected or not. */
	public void setAlpha(float alpha) { return; }

	/** Move the given Displayable to the next layer if possible. */
	public void moveDown(Layer layer, Displayable d) {
		int i = al_layers.indexOf(layer);
		if (al_layers.size() -1 == i || -1 == i) return;
		layer.remove(d);
		((Layer)(al_layers.get(i +1))).add(d);
	}
	/** Move the given Displayable to the previous layer if possible. */
	public void moveUp(Layer layer, Displayable d) {
		int i = al_layers.indexOf(layer);
		if (0 == i || -1 == i) return;
		layer.remove(d);
		((Layer)(al_layers.get(i -1))).add(d);
	}

	/** Move all Displayable objects in the HashSet to the given target layer. */
	public void move(final HashSet hs_d, final Layer source, final Layer target) {
		if (0 == hs_d.size() || null == source || null == target || source == target) return;
		Display.setRepaint(false); // disable repaints
		for (Iterator it = hs_d.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (source == d.getLayer()) {
				source.remove(d);
				target.add(d, false, false); // these contortions to avoid repeated DB traffic
				d.updateInDatabase("layer_id");
				Display.add(target, d, false); // don't activate
			}
		}
		Display.setRepaint(true); // enable repaints
		source.updateInDatabase("stack_index");
		target.updateInDatabase("stack_index");
		Display.repaint(source); // update graphics: true
		Display.repaint(target);
	}

	/** Find ZDisplayable objects that contain the point x,y in the given layer. */
	public Collection<Displayable> findZDisplayables(final Layer layer, final int x, final int y, final boolean visible_only) {
		if (null != root) return root.find(x, y, layer, visible_only);
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (zd.contains(layer, x, y)) al.add(zd);
		}
		return al;
	}
	public Collection<Displayable> findZDisplayables(final Layer layer, final Rectangle r, final boolean visible_only) {
		if (null != root) return root.find(r, layer, visible_only);
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (zd.getBounds(null, layer).intersects(r)) al.add(zd);
		}
		return al;
	}

	/** Returns the hash set of objects whose visibility has changed. */
	public HashSet<Displayable> setVisible(String type, final boolean visible, final boolean repaint) {
		type = type.toLowerCase();
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		try {
			project.getLoader().startLargeUpdate();
			if (type.equals("pipe") || type.equals("ball") || type.equals("arealist") || type.equals("polyline") || type.equals("stack") || type.equals("dissector")) {
				for (ZDisplayable zd : al_zdispl) {
					if (visible != zd.isVisible() && zd.getClass().getName().toLowerCase().endsWith(type)) { // endsWith, because DLabel is called as Label
						zd.setVisible(visible, false); // don't repaint
						hs.add(zd);
					}
				}
			} else {
				for (Layer layer : al_layers) {
					hs.addAll(layer.setVisible(type, visible, false)); // don't repaint
				}
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			project.getLoader().commitLargeUpdate();
		}
		if (repaint) {
			Display.repaint(this); // this could be optimized to repaint only the accumulated box
		}
		return hs;
	}
	/** Hide all except those whose type is in 'type' list, whose visibility flag is left unchanged. Returns the list of displayables made hidden. */
	public HashSet<Displayable> hideExcept(ArrayList<Class> type, boolean repaint) {
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (!type.contains(zd.getClass()) && zd.isVisible()) {
				zd.setVisible(false, repaint);
				hs.add(zd);
			}
		}
		for (Layer la : al_layers) hs.addAll(la.hideExcept(type, repaint));
		return hs;
	}
	/** Returns the collection of Displayable whose visibility state has changed. */
	public Collection<Displayable> setAllVisible(final boolean repaint) {
		final Collection<Displayable> col = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (!zd.isVisible()) {
				zd.setVisible(true, repaint);
				col.add(zd);
			}
		}
		for (Layer la : al_layers) col.addAll(la.setAllVisible(repaint));
		return col;
	}

	/** Returns true if any of the ZDisplayable objects are of the given class. */
	public boolean contains(final Class c) {
		for (ZDisplayable zd : al_zdispl) {
			if (zd.getClass() == c) return true;
		}
		return false;
	}
	/** Check in all layers. */
	public boolean containsDisplayable(Class c) {
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer la = (Layer)it.next();
			if (la.contains(c)) return true;
		}
		return false;
	}

	/** Returns the distance from the first layer's Z to the last layer's Z. */
	public double getDepth() {
		if (null == al_layers || al_layers.isEmpty()) return 0;
		return ((Layer)al_layers.get(al_layers.size() -1)).getZ() - ((Layer)al_layers.get(0)).getZ();
	}

	/** Return all the Displayable objects from all the layers of this LayerSet. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables() {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables());
		}
		return al;
	}
	/** Return all the Displayable objects from all the layers of this LayerSet of the given class. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables(Class c) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables(c));
		}
		return al;
	}
	/** Return all the Displayable objects from all the layers of this LayerSet of the given class that intersect the given area. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables(final Class c, final Area aroi, final boolean visible_only) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables(c, aroi, visible_only));
		}
		return al;
	}

	/** From zero to size-1. */
	public int indexOf(Layer layer) {
		return al_layers.indexOf(layer);
	}

	public void exportXML(final java.io.Writer writer, final String indent, final Object any) throws Exception {
		final StringBuffer sb_body = new StringBuffer();
		sb_body.append(indent).append("<t2_layer_set\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		sb_body.append(in).append("layer_width=\"").append(layer_width).append("\"\n")
		       .append(in).append("layer_height=\"").append(layer_height).append("\"\n")
		       .append(in).append("rot_x=\"").append(rot_x).append("\"\n")
		       .append(in).append("rot_y=\"").append(rot_y).append("\"\n")
		       .append(in).append("rot_z=\"").append(rot_z).append("\"\n")
		       .append(in).append("snapshots_quality=\"").append(snapshots_quality).append("\"\n")
		       .append(in).append("snapshots_mode=\"").append(snapshot_modes[snapshots_mode]).append("\"\n")
		       // TODO: alpha! But it's not necessary.
		;
		sb_body.append(indent).append(">\n");
		if (null != calibration) {
			sb_body.append(in).append("<t2_calibration\n")
			       .append(in).append("\tpixelWidth=\"").append(calibration.pixelWidth).append("\"\n")
			       .append(in).append("\tpixelHeight=\"").append(calibration.pixelHeight).append("\"\n")
			       .append(in).append("\tpixelDepth=\"").append(calibration.pixelDepth).append("\"\n")
			       .append(in).append("\txOrigin=\"").append(calibration.xOrigin).append("\"\n")
			       .append(in).append("\tyOrigin=\"").append(calibration.yOrigin).append("\"\n")
			       .append(in).append("\tzOrigin=\"").append(calibration.zOrigin).append("\"\n")
			       .append(in).append("\tinfo=\"").append(calibration.info).append("\"\n")
			       .append(in).append("\tvalueUnit=\"").append(calibration.getValueUnit()).append("\"\n")
			       .append(in).append("\ttimeUnit=\"").append(calibration.getTimeUnit()).append("\"\n")
			       .append(in).append("\tunit=\"").append(calibration.getUnit()).append("\"\n")
			       .append(in).append("/>\n")
			;
		}
		writer.write(sb_body.toString());
		// export ZDisplayable objects
		if (null != al_zdispl) {
			for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
				ZDisplayable zd = (ZDisplayable)it.next();
				sb_body.setLength(0);
				zd.exportXML(sb_body, in, any);
				writer.write(sb_body.toString()); // each separately, for they can be huge
			}
		}
		// export Layer and contained Displayable objects
		if (null != al_layers) {
			//Utils.log("LayerSet " + id + " is saving " + al_layers.size() + " layers.");
			for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
				sb_body.setLength(0);
				((Layer)it.next()).exportXML(sb_body, in, any);
				writer.write(sb_body.toString());
			}
		}
		super.restXML(sb_body, in, any);
		writer.write("</t2_layer_set>\n");
	}

	/** Includes the !ELEMENT */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_layer_set";
		if (!hs.contains(type)) {
			sb_header.append(indent).append("<!ELEMENT t2_layer_set (").append(Displayable.commonDTDChildren()).append(",t2_layer,t2_pipe,t2_ball,t2_area_list,t2_calibration,t2_stack,t2_treeline)>\n");
			Displayable.exportDTD(type, sb_header, hs, indent);
			sb_header.append(indent).append(TAG_ATTR1).append(type).append(" layer_width").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" layer_height").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_x").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_y").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_z").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" snapshots_quality").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" snapshots_mode").append(TAG_ATTR2)
			;
			sb_header.append(indent).append("<!ELEMENT t2_calibration EMPTY>\n")
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelWidth").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelHeight").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelDepth").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration xOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration yOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration zOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration info").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration valueUnit").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration timeUnit").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration unit").append(TAG_ATTR2)
			;
		}
	}

	public void setSnapshotsMode(final int mode) {
		if (mode == snapshots_mode) return;
		this.snapshots_mode = mode;
		Display.repaintSnapshots(this);
		updateInDatabase("snapshots_mode");
	}

	public int getSnapshotsMode() {
		return this.snapshots_mode;
	}

	public void destroy() {
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			layer.destroy();
		}
		for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
			ZDisplayable zd = (ZDisplayable)it.next();
			zd.destroy();
		}
		this.al_layers.clear();
		this.al_zdispl.clear();
		if (null != align) {
			align.destroy();
			align = null;
		}
	}

	public boolean isAligning() {
		return null != align;
	}

	public void cancelAlign() {
		if (null != align) {
			align.cancel(); // will repaint
			align = null;
		}
	}

	public void applyAlign(final boolean post_register) {
		if (null != align) align.apply(post_register);
	}

	public void applyAlign(final Layer la_start, final Layer la_end, final Selection selection) {
		if (null != align) align.apply(la_start, la_end, selection);
	}

	public void startAlign(Display display) {
		align = new Align(display);
	}

	public Align getAlign() {
		return align;
	}

	/** Used by the Layer.setZ method. */
	protected void reposition(Layer layer) {
		if (null == layer || !al_layers.contains(layer)) return;
		al_layers.remove(layer);
		addSilently(layer);
	}

	/** Get up to 'n' layers before and after the given layers. */
	public ArrayList getNeighborLayers(final Layer layer, final int n) {
		final int i_layer = al_layers.indexOf(layer);
		final ArrayList al = new ArrayList();
		if (-1 == i_layer) return al;
		int start = i_layer - n;
		if (start < 0) start = 0;
		int end = i_layer + n;
		if (end > al_layers.size()) end = al_layers.size();
		for (int i=start; i<i_layer; i++) al.add(al_layers.get(i));
		for (int i=i_layer+1; i<= i_layer + n || i < end; i++) al.add(al_layers.get(i));
		return al;
	}

	public boolean isTop(ZDisplayable zd) {
		if (null != zd && al_zdispl.size() > 0 && al_zdispl.indexOf(zd) == al_zdispl.size() -1) return true;
		return false;
	}

	public boolean isBottom(ZDisplayable zd) {
		if (null != zd && al_zdispl.size() > 0 && al_zdispl.indexOf(zd) == 0) return true;
		return false;
	}

	/** Hub method: ZDisplayable or into the Displayable's Layer. */
	protected boolean isTop(Displayable d) {
		if (d instanceof ZDisplayable) return isTop((ZDisplayable)d);
		else return d.getLayer().isTop(d);
	}
	/** Hub method: ZDisplayable or into the Displayable's Layer. */
	protected boolean isBottom(Displayable d) {
		if (d instanceof ZDisplayable) return isBottom((ZDisplayable)d);
		else return d.getLayer().isBottom(d);
	}

	/** Change z position in the layered stack, which defines the painting order. */ // the BOTTOM of the stack is the first element in the al_zdispl array
	synchronized protected void move(final int place, final Displayable d) {
		if (d instanceof ZDisplayable) {
			int i = al_zdispl.indexOf(d);
			if (-1 == i) {
				Utils.log("LayerSet.move: object does not belong here");
				return;
			}
			int size = al_zdispl.size();
			if (1 == size) return;
			switch(place) {
				case LayerSet.TOP:
					// To the end of the list:
					al_zdispl.add(al_zdispl.remove(i));
					if (null != root) root.update(this, d, i, al_zdispl.size()-1);
					break;
				case LayerSet.UP:
					// +1 in the list
					if (size -1 == i) return;
					al_zdispl.add(i+1, al_zdispl.remove(i));
					if (null != root) root.update(this, d, i, i+1);
					break;
				case LayerSet.DOWN:
					// -1 in the list
					if (0 == i) return;
					al_zdispl.add(i-1, al_zdispl.remove(i)); //swap
					if (null != root) root.update(this, d, i-1, i);
					break;
				case LayerSet.BOTTOM:
					// to first position in the list
					al_zdispl.add(0, al_zdispl.remove(i));
					if (null != root) root.update(this, d, 0, i);
					break;
			}
			updateInDatabase("stack_index");
			Display.updatePanelIndex(d.getLayer(), d);
		} else {
			switch (place) {
				case LayerSet.TOP: d.getLayer().moveTop(d); break;
				case LayerSet.UP: d.getLayer().moveUp(d); break;
				case LayerSet.DOWN: d.getLayer().moveDown(d); break;
				case LayerSet.BOTTOM: d.getLayer().moveBottom(d); break;
			}
		}
	}

	public int indexOf(final ZDisplayable zd) {
		int k = al_zdispl.indexOf(zd);
		if (-1 == k) return -1;
		return al_zdispl.size() - k -1;
	}

	public boolean isEmptyAt(Layer la) {
		for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
			if (((ZDisplayable)it.next()).paintsAt(la)) return false;
		}
		return true;
	}

	public Displayable clone(final Project pr, final boolean copy_id) {
		return clone(pr, (Layer)al_layers.get(0), (Layer)al_layers.get(al_layers.size()-1), new Rectangle(0, 0, (int)Math.ceil(getLayerWidth()), (int)Math.ceil(getLayerHeight())), false, copy_id);
	}

	/** Clone the contents of this LayerSet, from first to last given layers, and cropping for the given rectangle. */
	public Displayable clone(Project pr, Layer first, Layer last, Rectangle roi, boolean add_to_tree, boolean copy_id) {
		// obtain a LayerSet
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final LayerSet copy = new LayerSet(pr, nid, getTitle(), this.width, this.height, this.rot_x, this.rot_y, this.rot_z, roi.width, roi.height, this.locked, this.snapshots_mode, (AffineTransform)this.at.clone());
		copy.setCalibration(getCalibrationCopy());
		copy.snapshots_quality = this.snapshots_quality;
		// copy objects that intersect the roi, from within the given range of layers
		final java.util.List<Layer> range = ((ArrayList<Layer>)al_layers.clone()).subList(indexOf(first), indexOf(last) +1);
		Utils.log2("range.size() : " + range.size());
		for (Layer layer : range) {
			Layer layercopy = layer.clone(pr, copy, roi, copy_id);
			copy.addSilently(layercopy);
			if (add_to_tree) pr.getLayerTree().addLayer(copy, layercopy);
		}
		// copy ZDisplayable objects if they intersect the roi, and translate them properly
		final AffineTransform trans = new AffineTransform();
		trans.translate(-roi.x, -roi.y);
		for (ZDisplayable zd : find(first, last, new Area(roi))) {
			ZDisplayable zdcopy = (ZDisplayable)zd.clone(pr, copy_id);
			zdcopy.getAffineTransform().preConcatenate(trans);
			copy.addSilently(zdcopy); // must be added before attempting to crop it, because crop needs a LayerSet ref.
			if (zdcopy.crop(range)) {
				if (zdcopy.isDeletable()) {
					zdcopy.remove2(false); // from trees and all.
					Utils.log("Skipping empty " + zdcopy);
				}
			} else {
				Utils.log("Could not crop " + zd);
			}
		}
		// fix links:
		copy.linkPatchesR();
		return (Displayable)copy;
	}

	/** Create a virtual layer stack that acts as a virtual ij.ImageStack, in RGB and set to a scale of max_dimension / Math.max(layer_width, layer_height). */
	public LayerStack createLayerStack(Class clazz, int type, int c_alphas) {
		return new LayerStack(this,
				      getVirtualizationScale(),
				      type,
				      clazz,
				      c_alphas);
	}

	public int getPixelsMaxDimension() { return max_dimension; }
	/** From 0.000... to 1. */
	public double getVirtualizationScale() {
		double scale = max_dimension / Math.max(layer_width, layer_height);
		return scale > 1 ? 1 : scale;
	}
	public void setPixelsMaxDimension(int d) {
		if (d > 2 && d != max_dimension) {
			max_dimension = d;
			Polyline.flushTraceCache(project); // depends on the scale value
		} else Utils.log("Can't set virtualization max pixels dimension to smaller than 2!");
	}

	public void setPixelsVirtualizationEnabled(boolean b) { this.virtualization_enabled = b; }
	public boolean isPixelsVirtualizationEnabled() { return virtualization_enabled; }


	/** Returns a new Rectangle of 0, 0, layer_width, layer_height. */
	public Rectangle get2DBounds() {
		return new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
	}

	/** Set the calibration to a clone of the given calibration. */
	public void setCalibration(Calibration cal) {
		if (null == cal) return;
		this.calibration = (Calibration)cal.clone();
	}

	public Calibration getCalibration() {
		return this.calibration;
	}

	public Calibration getCalibrationCopy() {
		return calibration.copy();
	}

	public boolean isCalibrated() {
		Calibration identity = new Calibration();
		if (identity.equals(this.calibration)) return false;
		return true;
	}

	/** Restore calibration from the given XML attributes table.*/
	public void restoreCalibration(HashMap ht_attributes) {
		for (Iterator it = ht_attributes.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String value = (String)entry.getValue();
			// remove the prefix 't2_'
			key.substring(3).toLowerCase(); // case-resistant
			try {
				if (key.equals("pixelwidth")) {
					calibration.pixelWidth = Double.parseDouble(value);
				} else if (key.equals("pixelheight")) {
					calibration.pixelHeight = Double.parseDouble(value);
				} else if (key.equals("pixeldepth")) {
					calibration.pixelDepth = Double.parseDouble(value);
				} else if (key.equals("xorigin")) {
					calibration.xOrigin = Double.parseDouble(value);
				} else if (key.equals("yorigin")) {
					calibration.yOrigin = Double.parseDouble(value);
				} else if (key.equals("zorigin")) {
					calibration.zOrigin = Double.parseDouble(value);
				} else if (key.equals("info")) {
					calibration.info = value;
				} else if (key.equals("valueunit")) {
					calibration.setValueUnit(value);
				} else if (key.equals("timeunit")) {
					calibration.setTimeUnit(value);
				} else if (key.equals("unit")) {
					calibration.setUnit(value);
				}
			} catch (Exception e) {
				Utils.log2("LayerSet.restoreCalibration, key/value failed:" + key + "=\"" + value +"\"");
				IJError.print(e);
			}
		}
		//Utils.log2("Restored LayerSet calibration: " + calibration);
	}

	/** For creating snapshots, using a very slow but much better scaling algorithm (the Image.SCALE_AREA_AVERAGING method). */
	public boolean snapshotsQuality() {
		return snapshots_quality;
	}

	public void setSnapshotsQuality(boolean b) {
		this.snapshots_quality = b;
		updateInDatabase("snapshots_quality");
		// TODO this is obsolete
	}

	/** Find, in this LayerSet and contained layers and their nested LayerSets if any, all Displayable instances of Class c. Includes the ZDisplayables. */
	public ArrayList get(final Class c) {
		return get(new ArrayList(), c);
	}

	/** Find, in this LayerSet and contained layers and their nested LayerSets if any, all Displayable instances of Class c, which are stored in the given ArrayList; returns the same ArrayList, or a new one if its null. Includes the ZDisplayables. */
	public ArrayList get(ArrayList all, final Class c) {
		if (null == all) all = new ArrayList();
		// check whether to include all the ZDisplayable objects
		if (Displayable.class == c || ZDisplayable.class == c) all.addAll(al_zdispl);
		else {
			for (Iterator it = al_zdispl.iterator(); it.hasNext(); ){
				Object ob = it.next();
				if (ob.getClass() == c) all.add(ob);
			}
		}
		for (Layer layer : al_layers) {
			all.addAll(layer.getDisplayables(c));
			ArrayList al_ls = layer.getDisplayables(LayerSet.class);
			for (Iterator i2 = al_ls.iterator(); i2.hasNext(); ) {
				LayerSet ls = (LayerSet)i2.next();
				ls.get(all, c);
			}
		}
		return all;
	}

	/** Returns the region defined by the rectangle as an image in the type and format specified.
	 *  The type is either ImagePlus.GRAY8 or ImagePlus.COLOR_RGB.
	 *  The format is either Layer.IMAGE (an array) or Layer.ImagePlus (it returns an ImagePlus containing an ImageStack), from which any ImageProcessor or pixel arrays can be retrieved trivially.
	 */
	public Object grab(final int first, final int last, final Rectangle r, final double scale, final Class c, final int c_alphas, final int format, final int type) {
		// check preconditions
		if (first < 0 || first > last || last >= al_layers.size()) {
			Utils.log("Invalid first and/or last layers.");
			return null;
		}
		// check that it will fit in memory
		if (!project.getLoader().releaseToFit(r.width, r.height, type, 1.1f)) {
			Utils.log("LayerSet.grab: Cannot fit an image stack of " + (long)(r.width*r.height*(ImagePlus.GRAY8==type?1:4)*1.1) + " bytes in memory.");
			return null;
		}
		if (Layer.IMAGEPLUS == format) {
			ImageStack stack = new ImageStack((int)(r.width*scale), (int)(r.height*scale));
			for (int i=first; i<=last; i++) {
				Layer la = (Layer)al_layers.get(i);
				Utils.log2("c is " + c);
				ImagePlus imp = project.getLoader().getFlatImage(la, r, scale, c_alphas, type, c, null, true);
				if (null != imp) try {
					//if (0 == stack.getSize()) stack.setColorModel(imp.getProcessor().getColorModel());
					stack.addSlice(imp.getTitle(), imp.getProcessor()); //.getPixels());
				} catch (IllegalArgumentException iae) {
					IJError.print(iae);
				} else Utils.log("LayerSet.grab: Ignoring layer " + la);
			}
			if (0 == stack.getSize()) {
				Utils.log("LayerSet.grab: could not make slices.");
				return null;
			}
			return new ImagePlus("Stack " + first + "-" + last, stack);
		} else if (Layer.IMAGE == format) {
			final Image[] image = new Image[last - first + 1];
			for (int i=first, j=0; i<=last; i++, j++) {
				image[j] = project.getLoader().getFlatAWTImage((Layer)al_layers.get(i), r, scale, c_alphas, type, c, null, true, Color.black);
			}
			return image;
		}
		return null;
	}


	/** Searches in all layers. Ignores the ZDisplaybles. */
	public Displayable findDisplayable(final long id) {
		for (Layer la : al_layers) {
			for (Displayable d : la.getDisplayables()) {
				if (d.getId() == id) return d;
			}
		}
		return null;
	}

	/** Searches in all ZDisplayables and in all layers, recursively into nested LayerSets. */
	public DBObject findById(final long id) {
		if (this.id == id) return this;
		for (ZDisplayable zd : al_zdispl) {
			if (zd.getId() == id) return zd;
		}
		for (Layer la : al_layers) {
			DBObject dbo = la.findById(id);
			if (null != dbo) return dbo;
		}
		return null;
	}

	// private to the package
	void linkPatchesR() {
		for (Layer la : al_layers) la.linkPatchesR();
		for (ZDisplayable zd : al_zdispl) zd.linkPatches();
	}

	/** Recursive into nested LayerSet objects.*/
	public void updateLayerTree() {
		for (Layer la : al_layers) {
			la.updateLayerTree();
		}
	}

	/** Find the ZDisplayable objects that intersect with the 3D roi defined by the first and last layers, and the area -all in world coordinates. */
	public ArrayList<ZDisplayable> find(final Layer first, final Layer last, final Area area) {
		final ArrayList<ZDisplayable> al = new ArrayList<ZDisplayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (zd.intersects(area, first.getZ(), last.getZ())) {
				al.add(zd);
			}
		}
		return al;
	}

	/** For fast search. */
	Bucket root = null;
	private HashMap<Displayable,ArrayList<Bucket>> db_map = null;

	/** Returns a copy of the list of ZDisplayable objects. */
	public ArrayList<ZDisplayable> getZDisplayables() { return (ArrayList<ZDisplayable>)al_zdispl.clone(); }

	/** Returns the real list of displayables, not a copy. If you modify this list, Thor may ground you with His lightning. */
	public ArrayList<ZDisplayable> getDisplayableList() {
		return al_zdispl;
	}

	public HashMap<Displayable, ArrayList<Bucket>> getBucketMap() {
		return db_map;
	}

	public void updateBucket(final Displayable d) {
		if (null != root) root.updatePosition(d, db_map);
	}

	public void recreateBuckets(final boolean layers) {
		this.root = new Bucket(0, 0, (int)(0.00005 + getLayerWidth()), (int)(0.00005 + getLayerHeight()), Bucket.getBucketSide(this));
		this.db_map = new HashMap<Displayable,ArrayList<Bucket>>();
		this.root.populate(this, db_map);
		if (layers) {
			for (final Layer la : al_layers) {
				// recreate only if there were any already
				if (null != la.root) la.recreateBuckets();
			}
		}
	}

	/** Checks only buckets for ZDisplayable, not any related to any layer. */
	public void checkBuckets() {
		if (null == root || null == db_map) recreateBuckets(false);
	}

	public Rectangle getMinimalBoundingBox(final Class c) {
		Rectangle r = null;
		for (final Layer la : al_layers) {
			if (null == r) r = la.getMinimalBoundingBox(c);
			else {
				Rectangle box = la.getMinimalBoundingBox(c); // may be null if Layer is empty
				if (null != box) r.add(box);
			}
		}
		return r;
	}

	/** Time vs DoStep. Not all steps may be specific for a single Displayable. */
	final private TreeMap<Long,DoStep> edit_history = new TreeMap<Long,DoStep>();

	/** The step representing the current diff state. */
	private long current_edit_time = 0;
	private DoStep current_edit_step = null;

	/** Displayable vs its own set of time vs DoStep, for quick access, for those edits that are specific of a Displayable.
	 *  It's necessary to set a ground, starting point for any Displayable whose data will be edited. */
	final private Map<Displayable,TreeMap<Long,DoStep>> dedits = new HashMap<Displayable,TreeMap<Long,DoStep>>();

	/** Time vs DoStep; as steps are removed from the end of edit_history, they are put here. */
	final private TreeMap<Long,DoStep> redo = new TreeMap<Long,DoStep>();

	/** Whether an initial step should be added or not. */
	final boolean prepareStep(final Object ob) {
		synchronized (edit_history) {
			if (0 == edit_history.size() || redo.size() > 0) return true;
			// Check if the last added entry contains the exact same elements and data
			DoStep step = edit_history.get(edit_history.lastKey());
			boolean b = step.isIdenticalTo(ob);
			Utils.log2(b + " == prepareStep for " + ob);
			// If identical, don't prepare one!
			return !b;
		}
	}

	/** If last step is not a DoEdit "data" step for d, then call addDataEditStep(d). */
	boolean addPreDataEditStep(final Displayable d) {
		if (  null == current_edit_step
		  || (current_edit_step.getD() != d || !((DoEdit)current_edit_step).containsKey("data"))) {
			//Utils.log2("Adding pre-data edit step");
			//return addDataEditStep(d);
			return addEditStep(new Displayable.DoEdit(d).init(d, new String[]{"data"}));
		}
		return false;
	}

	/** A new undo step for the "data" field of Displayable d. */
	boolean addDataEditStep(final Displayable d) {
		//Utils.log2("Adding data edit step");
		// Adds "data", which contains width,height,affinetransform,links, and the data (points, areas, etc.)
		return addDataEditStep(d, new String[]{"data"});
	}
	/** A new undo step for any desired fields of Displayable d. */
	boolean addDataEditStep(final Displayable d, final String[] fields) {
		return addEditStep(new Displayable.DoEdit(d).init(d, fields));
	}

	/** A new undo step for the "data" field of all Displayable in the set. */
	boolean addDataEditStep(final Set<? extends Displayable> ds) {
		return addDataEditStep(ds, new String[]{"data"});
	}

	boolean addDataEditStep(final Set<? extends Displayable> ds, final String[] fields) {
		final Displayable.DoEdits edits = new Displayable.DoEdits(ds);
		edits.init(fields);
		return addEditStep(edits);
	}

	/** Add an undo step for the transformations of all Displayable in the layer. */
	public void addTransformStep(final Layer layer) {
		addTransformStep(layer.getDisplayables());
	}
	/** Add an undo step for the transformations of all Displayable in hs. */
	public void addTransformStep(final Collection<? extends Displayable> col) {
		//Utils.log2("Added transform step for col");
		addEditStep(new Displayable.DoTransforms().addAll(col));
	}
	/** Add an undo step for the transformations of all Displayable in all layers. */
	public void addTransformStep() {
		//Utils.log2("Added transform step for all");
		Displayable.DoTransforms dt = new Displayable.DoTransforms();
		for (final Layer la : al_layers) {
			dt.addAll(la.getDisplayables());
		}
		addEditStep(dt);
	}

	/** Add a step to undo the addition or deletion of one or more objects in this project and LayerSet. */
	public DoChangeTrees addChangeTreesStep() {
		DoChangeTrees step = new LayerSet.DoChangeTrees(this);
		if (prepareStep(step)) {
			Utils.log2("Added change trees step.");
			addEditStep(step);
		}
		return step;
	}
	/** Add a step to undo the addition or deletion of one or more objects in this project and LayerSet,
	 *  along with an arbitrary set of steps that may alter, for example the data. */
	public DoChangeTrees addChangeTreesStep(final Set<DoStep> dependents) {
		DoChangeTrees step = addChangeTreesStep();
		step.addDependents(dependents);
		addEditStep(step);
		return step;
	}
	/** For the Displayable contained in a Layer: their number, and their stack order. */
	public void addLayerContentStep(final Layer la) {
		DoStep step = new Layer.DoContentChange(la);
		if (prepareStep(step)) {
			Utils.log2("Added layer content step.");
			addEditStep(step);
		}
	}
	/** For the Z and thickness of a layer. */
	public void addLayerEditedStep(final Layer layer) {
		addEditStep(new Layer.DoEditLayer(layer));
	}
	/** For the Z and thickness of a list of layers. */
	public void addLayerEditedStep(final List<Layer> al) {
		addEditStep(new Layer.DoEditLayers(al));
	}

	public void addUndoStep(final DoStep step) {
		addEditStep(step);
	}

	boolean addEditStep(final DoStep step) {
		if (null == step || step.isEmpty()) {
			Utils.log2("Warning: can't add empty step " + step);
			return false;
		}

		synchronized (edit_history) {
			// Check if it's identical to current step
			if (step.isIdenticalTo(current_edit_step)) {
				Utils.log2("Skipping identical undo step of class " + step.getClass() + ": " + step);
				return false;
			}

			// Store current in undo queue
			if (null != current_edit_step) {
				edit_history.put(current_edit_time, current_edit_step);
				// Store for speedy access, if its Displayable-specific:
				final Displayable d = current_edit_step.getD();
				if (null != d) {
					TreeMap<Long,DoStep> edits = dedits.get(d);
					if (null == edits) {
						edits = new TreeMap<Long,DoStep>();
						dedits.put(d, edits);
					}
					edits.put(current_edit_time, current_edit_step);
				}

				// prune if too large
				while (edit_history.size() > project.getProperty("n_undo_steps", 32)) {
					long t = edit_history.firstKey();
					DoStep st = edit_history.remove(t);
					if (null != st.getD()) {
						TreeMap<Long,DoStep> m = dedits.get(st.getD());
						m.remove(t);
						if (0 == m.size()) dedits.remove(st.getD());
					}
				}
			}

			// Set step as current
			current_edit_time = System.currentTimeMillis();
			current_edit_step = step;

			// Bye bye redo! Can't branch.
			redo.clear();
		}

		return true;
	}

	public boolean canUndo() {
		return edit_history.size() > 0;
	}
	public boolean canRedo() {
		return redo.size() > 0 || null != current_edit_step;
	}

	/** Undoes one step of the ongoing transformation history, otherwise of the overall LayerSet history. */
	public boolean undoOneStep() {
		synchronized (edit_history) {
			if (0 == edit_history.size()) {
				Utils.logAll("Empty undo history!");
				return false;
			}

			//Utils.log2("Undoing one step");

			// Add current (if any) to redo queue
			if (null != current_edit_step) {
				redo.put(current_edit_time, current_edit_step);
			}

			// Remove last step from undo queue, and set it as current
			current_edit_time = edit_history.lastKey();
			current_edit_step = edit_history.remove(current_edit_time);

			// Remove as well from dedits
			if (null != current_edit_step.getD()) {
				dedits.get(current_edit_step.getD()).remove(current_edit_time);
			}

			if (!current_edit_step.apply(DoStep.UNDO)) {
				Utils.log("Undo: could not apply step!");
				return false;
			}

			Utils.log("Undoing " + current_edit_step.getClass().getSimpleName());

			Display.updateVisibleTabs(project);
		}
		return true;
	}

	/** Redoes one step of the ongoing transformation history, otherwise of the overall LayerSet history. */
	public boolean redoOneStep() {
		synchronized (edit_history) {
			if (0 == redo.size()) {
				Utils.logAll("Empty redo history!");
				if (null != current_edit_step) {
					return current_edit_step.apply(DoStep.REDO);
				}
				return false;
			}

			//Utils.log2("Redoing one step");

			// Add current (if any) to undo queue
			if (null != current_edit_step) {
				edit_history.put(current_edit_time, current_edit_step);
				if (null != current_edit_step.getD()) {
					dedits.get(current_edit_step.getD()).put(current_edit_time, current_edit_step);
				}
			}

			// Remove one step from undo queue and set it as current
			current_edit_time = redo.firstKey();
			current_edit_step = redo.remove(current_edit_time);

			if (!current_edit_step.apply(DoStep.REDO)) {
				Utils.log("Undo: could not apply step!");
				return false;
			}

			Utils.log("Redoing " + current_edit_step.getClass().getSimpleName());

			Display.updateVisibleTabs(project);
		}
		return true;
	}

	static public void applyTransforms(final Map<Displayable,AffineTransform> m) {
		for (final Map.Entry<Displayable,AffineTransform> e : m.entrySet()) {
			e.getKey().setAffineTransform(e.getValue()); // updates buckets
		}
	}

	static {
		// Undo background tasks: should be done in background threads,
		// but on attempting to undo/redo, the undo/redo should wait
		// until all tasks are done. For example, updating mipmaps when
		// undoing/redoing min/max or CoordinateTransform.
		// This could be done with futures: spawn and do in the
		// background, but on redo/undo, call for the Future return
		// value, which will block until there is one to return.
		// Since blocking would block the EventDispatchThread, just refuse to undo/redo and notify the user.
		//
		// TODO
	}

	/** Keeps the width,height of a LayerSet and the AffineTransform of every Displayable in it. */
	static private class DoResizeLayerSet implements DoStep {

		final LayerSet ls;
		final HashMap<Displayable,AffineTransform> affines;
		final double width, height;

		DoResizeLayerSet(final LayerSet ls) {
			this.ls = ls;
			this.width = ls.layer_width;
			this.height = ls.layer_height;
			this.affines = new HashMap<Displayable,AffineTransform>();

			final ArrayList<Displayable> col = ls.getDisplayables(); // it's a new list
			col.addAll(ls.getZDisplayables());
			for (final Displayable d : col) {
				this.affines.put(d, d.getAffineTransformCopy());
			}
		}
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof LayerSet)) return false;
			final LayerSet layerset = (LayerSet) ob;
			if (layerset.layer_width != this.width || layerset.height != this.height || layerset != this.ls) return false;
			final ArrayList<Displayable> col = ls.getDisplayables();
			col.addAll(ls.getZDisplayables());
			for (final Displayable d : col) {
				final AffineTransform aff = this.affines.get(d);
				if (null == aff) return false;
				if (!aff.equals(d.getAffineTransform())) return false;
			}
			return true;
		}

		public boolean apply(int action) {
			ls.layer_width = width;
			ls.layer_height = height;
			for (final Map.Entry<Displayable,AffineTransform> e : affines.entrySet()) {
				e.getKey().getAffineTransform().setTransform(e.getValue());
			}
			if (null != ls.root) ls.recreateBuckets(true);
			Display.updateSelection();
			Display.update(ls); //so it's not left out painted beyond borders
			return true;
		}
		public boolean isEmpty() { return false; }
		public Displayable getD() { return null; }
	}

	/** Records the state of the LayerSet.al_layers, each Layer.al_displayables and all the trees and unique types of Project. */
	static private class DoChangeTrees implements DoStep {
		final LayerSet ls;
		final HashMap<Thing,Boolean> ttree_exp, ptree_exp, ltree_exp;
		final Thing troot, proot, lroot;
		final ArrayList<Layer> all_layers;
		final HashMap<Layer,ArrayList<Displayable>> all_displ;
		final ArrayList<ZDisplayable> all_zdispl;
		final HashMap<Displayable,Set<Displayable>> links;

		HashSet<DoStep> dependents = null;

		// TODO: does not consider recursive LayerSets!
		public DoChangeTrees(final LayerSet ls) {
			this.ls = ls;
			final Project p = ls.getProject();

			this.ttree_exp = new HashMap<Thing,Boolean>();
			this.troot = p.getTemplateTree().duplicate(ttree_exp);
			this.ptree_exp = new HashMap<Thing,Boolean>();
			this.proot = p.getProjectTree().duplicate(ptree_exp);
			this.ltree_exp = new HashMap<Thing,Boolean>();
			this.lroot = p.getLayerTree().duplicate(ltree_exp);

			this.all_layers = ls.getLayers(); // a copy of the list, but each object is the running instance
			this.all_zdispl = ls.getZDisplayables(); // idem

			this.links = new HashMap<Displayable,Set<Displayable>>();
			for (final ZDisplayable zd : this.all_zdispl) {
				this.links.put(zd, zd.hs_linked); // LayerSet is a Displayable
			}

			this.all_displ = new HashMap<Layer,ArrayList<Displayable>>();
			for (final Layer layer : all_layers) {
				final ArrayList<Displayable> al = layer.getDisplayables(); // a copy
				this.all_displ.put(layer, al);
				for (final Displayable d : al) {
					this.links.put(d, null == d.hs_linked ? null : new HashSet<Displayable>(d.hs_linked));
				}
			}
		}
		public Displayable getD() { return null; }
		public boolean isEmpty() { return false; }
		public boolean isIdenticalTo(final Object ob) {
			// TODO
			return false;
		}
		public boolean apply(int action) {
			// Replace all layers
			ls.al_layers.clear();
			ls.al_layers.addAll(this.all_layers);

			final ArrayList<Displayable> patches = new ArrayList<Displayable>();

			// Replace all Displayable in each Layer
			for (final Map.Entry<Layer,ArrayList<Displayable>> e : all_displ.entrySet()) {
				// Acquire pointer to the actual instance list in each Layer
				final ArrayList<Displayable> al = e.getKey().getDisplayableList(); // the real one!
				// Create a list to contain those Displayable present in old list but not in list to use now
				final HashSet<Displayable> diff = new HashSet<Displayable>(al); // create with all Displayable of old list
				diff.removeAll(e.getValue()); // remove all Displayable present in list to use now, to leave the diff or remainder only
				// Clear current list
				al.clear();
				// Insert all to the current list
				al.addAll(e.getValue());
				// Add to remove-on-shutdown queue all those Patch no longer in the list to use now:
				for (final Displayable d : diff) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().tagForMipmapRemoval((Patch)d, true);
					}
				}
				// Remove from queue all those Patch in the list to use now:
				for (final Displayable d : al) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().tagForMipmapRemoval((Patch)d, false);
					}
				}
			}

			// Replace all ZDisplayable
			ls.al_zdispl.clear();
			ls.al_zdispl.addAll(this.all_zdispl);

			// Replace all trees
			final Project p = ls.getProject();
			p.getTemplateTree().set(this.troot, this.ttree_exp);
			p.getProjectTree().set(this.proot, this.ptree_exp);
			p.getLayerTree().set(this.lroot, this.ltree_exp);

			// Replace all links
			for (final Map.Entry<Displayable,Set<Displayable>> e : this.links.entrySet()) {
				final Set<Displayable> hs = e.getKey().hs_linked;
				if (null != hs) {
					final Set<Displayable> hs2 = e.getValue();
					if (null == hs2) e.getKey().hs_linked = null;
					else {
						hs.clear();
						hs.addAll(hs2);
					}
				}
			}

			// Invoke dependents
			if (null != dependents) for (DoStep step : dependents) step.apply(action);

			ls.recreateBuckets(true);

			Display.clearSelection(ls.project);
			Display.update(ls, false);

			return true;
		}

		synchronized public void addDependents(Set<DoStep> dep) {
			if (null == this.dependents) this.dependents = new HashSet<DoStep>();
			this.dependents.addAll(dep);
		}
	}

	private Overlay overlay = null;

	/** Return the current Overlay or a new one if none yet. */
	synchronized public Overlay getOverlay() {
		if (null == overlay) overlay = new Overlay();
		return overlay;
	}
	// Used by DisplayCanvas to paint
	Overlay getOverlay2() {
		return overlay;
	}
	/** Set to null to remove the Overlay.
	 *  @return the previous Overlay, if any. */
	synchronized public Overlay setOverlay(final Overlay o) {
		Overlay old = this.overlay;
		this.overlay = o;
		return old;
	}
}
