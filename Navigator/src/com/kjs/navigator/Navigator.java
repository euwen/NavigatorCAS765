package com.kjs.navigator;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;


import com.kjs.navigator.R;
import com.kjs.navigator.StepCounter.OnStepEventListener;
import com.qozix.tileview.TileView;
import com.qozix.tileview.TileView.TileViewEventListener;
import com.qozix.tileview.markers.MarkerEventListener;
import com.qozix.tileview.paths.DrawablePath;

import android.os.Bundle;
import android.os.Handler;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

public class Navigator extends Activity implements SensorEventListener, OnStepEventListener{

	private TileView tileView;
	private ImageView naviSymbol;
	private int currentX;
	private int currentY;
	private int roundedHeading;
	private double scale=1;
	private boolean gettingInitialPoint=false;
	private boolean gettingSecondaryPoint=false;
	private boolean firstAddSymbol=true;
	private ImageView startSymbol;
	private ImageView headingSymbol;
	private DrawablePath startPath;
	private StepCounter stepCounter;
	//private TileViewEventListener tileEventListener;

	//##### Public Variables #####

	private double CMPERPIXEL = 5;
	private int numParticles = 50; 			// Particle count
	private double[] stepLength= new double[numParticles]; //step length in cm 
	private Point startLocation;
	private Point headingLocation;
	//private Point[] particles = new Point[10];
	private Point currentLocation=new Point(0,0);
	private Polygon ITBhalls;

	private double previousStepTimestamp = 0;
	private SensorManager mSensorManager;
	private Sensor mAccelerometer, mCompass;


	//need to create initial particles X and Y cloud around initial position
	private Point[] particles = new Point[numParticles];
	double[] particleA = new double[numParticles];
	double[] particleB = new double[numParticles];

	double locationMean = 0.0;	//Mean will almost always be zero for location
	double locationStd = 5;		//standard deviation in pixels

	double aMean = 20.0;		//from figure 6 in paper, approx mean of slope
	double aStd = 10;			//guess at slope std

	double bMean = 30;			//from figure 6 in paper, approx value of offset
	double bStd = 10;			//guess at offset std

	Random rng = new Random();	//Random Number Generator spreads our particles in a gaussian distribution.

	//need to have a past history of heading values, maybe shift new values in
	ArrayList<Float> rawHeading = new ArrayList<Float>();
	int rawHeadingMAX = 9; 
	ArrayList<Float> headingAtStep = new ArrayList<Float>();
	int headingAtStepMAX = 50;

	int stepsSinceLastTurn = 0;
	double deltaHeadingThreshold = Math.PI/2;
	double averagePastHeading = 0; //TODO needs to be initialized it heading  
	float currentHeading = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_navigator);

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mCompass = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);

		tileView = new TileView( this );
		setContentView( tileView );
		// size of original image at 100% scale
		tileView.setSize( 2048, 1887 );

		// detail levels
		tileView.addDetailLevel( 1.000f, "tiles/3/%col%/%row%.png", "downsamples/map.png");
		tileView.addDetailLevel( 0.500f, "tiles/2/%col%/%row%.png", "downsamples/map.png");
		tileView.addDetailLevel( 0.250f, "tiles/1/%col%/%row%.png", "downsamples/map.png");
		tileView.addDetailLevel( 0.125f, "tiles/0/%col%/%row%.png", "downsamples/map.png");

		// let's use 0-1 positioning...
		tileView.defineRelativeBounds( 0, 0, 2048,  1887);

		// center markers along both axes
		tileView.setMarkerAnchorPoints( -0.5f, -0.5f );

		// add a marker listener
		//tileView.addMarkerEventListener( markerEventListener );

		tileView.addTileViewEventListener(tileEventListener);

		stepCounter= new StepCounter(this);
		// add some pins...
		//roundedHeading=0;
		//currentX=200;
		//currentY=200;
		Log.d("Created", "Current Position "+currentLocation.toString());
		naviSymbol = new ImageView( this );
		//naviSymbol.setImageBitmap(getBitmapFromAssets("pointer/naviPointer-"+0+".png"));
		firstAddSymbol=true;
		updateLocal(currentLocation.x,currentLocation.y,currentHeading);
		firstAddSymbol=false;
		Log.d("Created", "Current Position "+currentLocation.toString());
		//updateLocal(currentX,currentY,roundedHeading);
		// scale it down to manageable size
		tileView.setScale( 0.5 );
		// center the frame
		frameTo( 0.5, 0.5 );


	}// End onCreate




	//######### Custom Functions #############

	public void inputStartLocation()
	{
		if (!gettingInitialPoint && !gettingSecondaryPoint)
		{
			Log.d("Function Call","Input Start");

			gettingInitialPoint=true;
			//Log.d("Function Call","InputStart Finished");
		}
	}

	private void initializeParticleFilter(){

		//Generate particle cloud around starting location
		for (int i = 0; i<numParticles; i++) {
			float x = (float) (startLocation.x + locationMean + locationStd * rng.nextGaussian());
			float y = (float) (startLocation.y + locationMean + locationStd * rng.nextGaussian());
			particles[i] = new Point(x,y);

			particleA[i] = aMean + aStd * rng.nextGaussian();
			particleB[i] = bMean + bStd * rng.nextGaussian();
		}

		ITBhalls = Polygon.Builder()
				.addVertex(new Point(318, 154))
				.addVertex(new Point(397, 154))
				.addVertex(new Point(397, 275))
				.addVertex(new Point(1808, 275))
				.addVertex(new Point(1808, 1574))
				.addVertex(new Point(1463, 1574))
				.addVertex(new Point(1463, 1654))
				.addVertex(new Point(1164, 1654))
				.addVertex(new Point(1164, 1573))
				.addVertex(new Point(207, 1573))
				.addVertex(new Point(207, 1486))
				.addVertex(new Point(1880, 1486))
				.addVertex(new Point(1880, 965))
				.addVertex(new Point(1180, 965))
				.addVertex(new Point(1180, 890))
				.addVertex(new Point(1086, 890))
				.addVertex(new Point(1086, 860))
				.addVertex(new Point(1207, 860))
				.addVertex(new Point(1207, 950))
				.addVertex(new Point(1346, 950))
				.addVertex(new Point(1346, 1487))
				.addVertex(new Point(1736, 1487))
				.addVertex(new Point(1736, 340))
				.addVertex(new Point(422, 340))
				.addVertex(new Point(422, 516))
				.addVertex(new Point(318, 516))
				.close()
				.addVertex(new Point(920, 1005))
				.addVertex(new Point(920, 1490))
				.addVertex(new Point(1277, 1490))
				.addVertex(new Point(1277, 1005))
				.build();
	}

	public void createStartSymbol(int x, int y)
	{
		startSymbol = new ImageView( this);
		startSymbol.setImageResource( R.drawable.start );
		getTileView().addMarker(startSymbol, x, y );
		startLocation = new Point(x,y);   
		currentLocation=new Point(x,y);   
	}
	public void createHeadingSymbol(int x, int y)
	{
		headingSymbol = new ImageView( this);
		headingSymbol.setImageResource( R.drawable.heading );
		getTileView().addMarker(headingSymbol, x, y );
		headingLocation = new Point(x,y);  
		ArrayList<double[]> points = new ArrayList<double[]>();
		{
			points.add( new double[] { headingLocation.x, headingLocation.y } );
			points.add( new double[] { startLocation.x,  startLocation.y } );
		}
		startPath=tileView.drawPath(points);

	}
	public void initializeStartHeading()
	{
		int headingDeg=findHeading(startLocation.x,startLocation.y, headingLocation.x,headingLocation.y);
		Log.d("xHeading Created","Start Location X:"+startLocation.x+" Y:"+startLocation.y+" Heading Angle:"+headingDeg);
		for (int i=0;i<rawHeadingMAX;i++)
		{
			rawHeading.add((float) headingDeg);
			if (rawHeading.size() > rawHeadingMAX ){
				rawHeading.remove(0);
			}
		}
		currentHeading=headingDeg;
		updateLocal(startLocation.x,startLocation.y,headingDeg);
	}
	public void updatePosition()
	{
		int stepLength=10;
		currentLocation.x=(int)(currentLocation.x+stepLength*Math.sin(currentHeading*Math.PI/180));
		currentLocation.y=(int)(currentLocation.y-stepLength*Math.cos(currentHeading*Math.PI/180));
		Log.d("xPosition Updated","Current Location X:"+currentLocation.x+" Y:"+currentLocation.y+" Heading Angle:"+currentHeading);
		updateLocal(currentLocation.x,currentLocation.y,currentHeading);
	}
	public int findHeading(float x,float y, float x2,float y2)
	{
		double dX= x-x2;
		double dY= y-y2;
		//return (int)((Math.atan2(dY, dX)*180)/Math.PI);
		double arcTanVal=(Math.atan2(dY, dX)*180)/Math.PI;

		//return (int)(270-arcTanVal);
		double newVal= (360-(-arcTanVal+180)+90);
		//Log.d("Heading Raw","Heading Raw A:"+newVal);
		if (newVal>360)
		{
			newVal=newVal-360;
		}
		//Log.d("Heading Raw","Heading Raw B:"+newVal);
		return (int)newVal;
	}
	public void cleanupSymbols()
	{
		tileView.removeMarker(startSymbol);
		tileView.removeMarker(headingSymbol);
		tileView.removePath(startPath);
	}
	public void reset()
	{
		Log.d("Function Call","Reset");
		currentX+=10;
		currentY+=10; 
		roundedHeading=(roundedHeading+60)%360;
		updateLocal(currentX,currentY, roundedHeading);
	}
	public void start()
	{
		Log.d("Function Call","Start");
		stepCounter.pushdata(1, 2, 3);
		mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
		mSensorManager.registerListener(this, mCompass, SensorManager.SENSOR_DELAY_GAME);

	}
	private void stop() {
		// TODO Auto-generated method stub
		Log.d("Function Call","Stop");
		mSensorManager.unregisterListener(this, mAccelerometer);
		mSensorManager.unregisterListener(this, mCompass);
	}
	/*
	private void addPin( double x, double y ) {
		ImageView imageView = new ImageView( this );
		imageView.setImageResource( R.drawable.push_pin );
		getTileView().addMarker( imageView, x, y );
	}*/

	private void updateLocal( double x, double y ,double angle) {
		if (!firstAddSymbol)
		{
			getTileView().removeMarker(naviSymbol);

		}
		//naviSymbol = new ImageView( this );
		//naviSymbol.setImageBitmap(getBitmapFromAssets("pointer/naviPointer-"+angle+".png"));
		//naviSymbol.setImageResource( R.drawable.push_pin );
		naviSymbol.setImageBitmap(getBitmapFromAssets("pointer/naviPointer-"+(int)angle+".png"));
		getTileView().addMarker( naviSymbol, x, y );
		firstAddSymbol=false;
	}

	private MarkerEventListener markerEventListener = new MarkerEventListener() {
		@Override
		public void onMarkerTap( View v, int x, int y ) {
			Toast.makeText( getApplicationContext(), "You are here", Toast.LENGTH_LONG ).show();
		}		
	};


	public Bitmap getBitmapFromAssets(String fileName) {
		AssetManager assetManager = getAssets();

		InputStream istr = null;
		try {
			istr = assetManager.open(fileName); 
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Bitmap bitmap = BitmapFactory.decodeStream(istr);

		return bitmap;
	}
	//################## END OF CUSTOM FUNCTIONS #####################



	///##################SENSOR BEHAVIOUR############################
	@Override
	public void onSensorChanged(SensorEvent arg0) {
		if (arg0.sensor == mAccelerometer)
			stepCounter.pushdata(arg0.values[0], arg0.values[1], arg0.values[2]);
		else if (arg0.sensor == mCompass){
			if (rawHeading.size() > rawHeadingMAX ){
				rawHeading.remove(0);
			}
			rawHeading.add(arg0.values[0]);
		}

	}

	//#################### TILE TOUCH BEHAVIOUR #######################
	private TileViewEventListener tileEventListener = new TileViewEventListener() {

		@Override
		public void onDetailLevelChanged() {
			// TODO Auto-generated method stub

		}

		@Override
		public void onDoubleTap(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onDrag(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onFingerDown(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onFingerUp(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onFling(int arg0, int arg1, int arg2, int arg3) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onFlingComplete(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onPinch(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onPinchComplete(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onPinchStart(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onRenderComplete() {
			// TODO Auto-generated method stub

		}

		@Override
		public void onRenderStart() {
			// TODO Auto-generated method stub

		}

		@Override
		public void onScaleChanged(double arg0) {
			// TODO Auto-generated method stub
			scale=arg0;
		}

		@Override
		public void onScrollChanged(int arg0, int arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onTap(int arg0, int arg1) {
			// TODO Auto-generated method stub
			if (gettingInitialPoint)
			{
				Log.d("Tap Event","X:"+arg0+" Y:"+arg1);
				int scaledX=(int)(arg0/scale);
				int scaledY=(int)(arg1/scale);
				Log.d("Tap Event","Scaled X:"+scaledX+" Scaled Y:"+scaledY);
				createStartSymbol(scaledX,scaledY);
				gettingInitialPoint=false;
				gettingSecondaryPoint=true;
			}
			else if (gettingSecondaryPoint)
			{
				Log.d("Tap Event","Secondary X:"+arg0+" Y:"+arg1);
				int scaledX=(int)(arg0/scale);
				int scaledY=(int)(arg1/scale);
				Log.d("Tap Event","Secondary Scaled X:"+scaledX+" Scaled Y:"+scaledY);
				createHeadingSymbol(scaledX,scaledY);
				gettingSecondaryPoint=false;
				//Wipe the Symbols
				final Handler handler = new Handler();
				handler.postDelayed(new Runnable() {
					@Override
					public void run() {
						// Do something after 5s = 5000ms
						cleanupSymbols();
						initializeStartHeading();
						initializeParticleFilter();

					}
				}, 1000);
				//cleanupSymbols();
				//initializeParticleFilter();
			}

		}

		@Override
		public void onZoomComplete(double arg0) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onZoomStart(double arg0) {
			// TODO Auto-generated method stub

		}

	};
	private DrawablePath particlePath;
	private boolean firstPath=true;
	private DrawablePath badPath;
	//########## PREMADE STUFF ###############
	//Options Premade
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.navigator, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
		case R.id.action_inputStart:
			inputStartLocation();
			return true;
		case R.id.action_reset:
			reset();
			return true;
		case R.id.action_start:
			start();
			return true;
		case R.id.action_stop:
			stop();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}





	//Sensor Premade
	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// TODO Auto-generated method stub

	}
	// TILE Premade
	public TileView getTileView(){
		return tileView;
	}
	/**
	 * This is a convenience method to moveToAndCenter after layout (which won't happen if called directly in onCreate
	 * see https://github.com/moagrius/TileView/wiki/FAQ
	 */
	public void frameTo( final double x, final double y ) {
		getTileView().post( new Runnable() {
			@Override
			public void run() {
				getTileView().moveToAndCenter( x, y );
			}			
		});		
	}




	public void stepEvent() {
		// TODO Auto-generated method stub
		Log.d("Step event","Step Event triggered");
		stepTaken(System.currentTimeMillis()); 
		//updatePosition();

	}
	public float fixCompassReadings()
	{
		float first =shiftQuadrants(0);
		float second =shiftQuadrants(first);	
		if (first==second)
		{
			return first;
		}
		while (first!=second)
		{
			first =shiftQuadrants(second);
			second =shiftQuadrants(first);	
		}
		return first;
	}
	public float shiftQuadrants(float p)
	{
		//Find min and Max of array
		float max=0;
		float min=360;
		for (int i=0;i<rawHeading.size();i++)
		{
			if (rawHeading.get(i)>max)
			{
				max=rawHeading.get(i);
			}
			if (rawHeading.get(i)<min)
			{
				min=rawHeading.get(i);
			}

		}
		//If differ by 180 +180 to the mix 
		if (Math.abs(max-min)>180)
		{
			for (int i=0;i<rawHeading.size();i++)
			{
				rawHeading.set(i, (rawHeading.get(i)+p+90)%360);
			}
			return (p+90)%360;
		}
		return p%360;
	}
	public void logCompass()
	{
		for (int i=0;i<rawHeading.size();i++)
		{
			Log.d("Compass Readings","Reading "+i+": "+rawHeading.get(i));
		}
	}

	public void stepTaken(double currentStepTimestamp){
		//First, we shall try to determine if the user has turned

		//Perform filter on heading directions
		//Not sure how that is done
		//sort recent heading values
		logCompass();
		float shift=fixCompassReadings();
		Log.d("Compass Shift","Shift: "+shift); 
		logCompass();
		//Float[] sortedHeading = (Float[]) rawHeading.toArray();
		Collections.sort(rawHeading);
		//Arrays.sort(sortedHeading);
		logCompass();

		//TODO headingAtCurrentStep[] = ( ( sortedHeading[4] + sortedHeading[5] + sortedHeading[6] ) / 3 );
		//minHeading=Math.min(d1, d2)
		currentHeading = ( ( rawHeading.get(4) + rawHeading.get(5) + rawHeading.get(6) ) / 3 )-shift;
		Log.d("Compass Heading","Heading: "+currentHeading);
		headingAtStep.add(currentHeading);

		stepsSinceLastTurn++;
		double headingSum = 0;

		for (int i = 0; i<headingAtStep.size(); i++) {
			headingSum += headingAtStep.get(i);
		}
		double averagePastHeading = headingSum / stepsSinceLastTurn;

		double deltaHeading = headingAtStep.get(headingAtStep.size() - 1) - averagePastHeading;

		if (deltaHeading > deltaHeadingThreshold) {
			stepsSinceLastTurn = 0;
			headingAtStep.removeAll(headingAtStep);
		}

		//currentHeading = averagePastHeading;

		int totalX = 0;
		int totalY = 0;

		// calculate step frequency from step period
		double period = previousStepTimestamp - currentStepTimestamp;
		previousStepTimestamp = currentStepTimestamp;


		//Second, we update all of the particles, we also compute the average X and Y at this point
		for (int i = 0; i<numParticles; i++) {
			stepLength[i] = ( particleA[i] * 1/period) + particleB[i];


			float x = (float) (particles[i].x + ( ( stepLength[i] ) * Math.cos(currentHeading ) ));
			float y = (float) (particles[i].y + ( ( stepLength[i] ) * Math.sin(currentHeading ) ));
			particles[i] = new Point(x,y);	

			totalX += particles[i].x;
			totalY += particles[i].y;
		}

		int averageX = totalX / numParticles;
		int averageY = totalY / numParticles;

		totalX = 0;
		totalY = 0;

		int survivingParticles = 0;
		int[] particleNeedsReplacing = new int[numParticles];

		ArrayList<double[]> points = new ArrayList<double[]>();
		ArrayList<double[]> badPoints = new ArrayList<double[]>();
		for (int i = 0; i < numParticles; i++) {
			Log.d("Contains",""+!ITBhalls.contains(particles[i]));
			if (!ITBhalls.contains(particles[i])) {
				badPoints.add( new double[] { particles[i].x, particles[i].y } );
				particleNeedsReplacing[i] = 1;
			}
			else {
				survivingParticles ++;
				particleNeedsReplacing[i] = 0;
				points.add( new double[] { particles[i].x, particles[i].y } );
				totalX += particles[i].x;
				totalY += particles[i].y;
			}
		}
		float survivingAverageX;
		float survivingAverageY;
		if (survivingParticles>0)
		{
			survivingAverageX = totalX / survivingParticles;
			survivingAverageY = totalY / survivingParticles;
		}
		else
		{
			survivingAverageX=currentLocation.x;
			survivingAverageY=currentLocation.y;
		}
		for (int i = 0; i < numParticles; i++) {
			if (particleNeedsReplacing[i] == 1) {
				float x = (float) (survivingAverageX + 0.1*locationStd * rng.nextGaussian());
				float y = (float) (survivingAverageY + 0.1*locationStd * rng.nextGaussian());
				particles[i] = new Point(x,y);
			}

		}
		averageX = totalX / numParticles;
		averageY = totalY / numParticles;

		currentLocation = new Point (averageX, averageY);
		//DEBUG PARTICLES
		if (firstPath)
		{
			firstPath=false;
		}
		else
		{
			tileView.removePath(particlePath);
			tileView.removePath(badPath);
		}
		Paint r= new Paint();
		r.setColor(Color.RED);
		particlePath = tileView.drawPath(points);

		if(badPoints.size()>0) badPath = tileView.drawPath(badPoints,r);
		//tileView.drawPath(positions, paint)


		updateLocal(currentLocation.x,currentLocation.y,currentHeading);



		//TODO: plot current position
	}


	//############## Unused Stuff #####################
	/*
	private HotSpot hotMap;
	private HotSpotEventListener startHotSpotEventListener = new HotSpotEventListener() {
		@Override
		public void onHotSpotTap( HotSpot h, int x, int y ) {
			Log.d("Variables", "Initial Position X: "+x+" Y: "+y);
			//tileView.removeHotSpot(hotMap);


		}		
	};*/
	//HotSpot allMap= new HotSpot(0,0,2048,1887);
	//allMap.setHotSpotEventListener(startHotSpotEventListener);
	/*ArrayList<double[]> points = new ArrayList<double[]>();
	{
		points.add( new double[] { 0, 0 } );
		points.add( new double[] { 0, 100 } );
		points.add( new double[] { 100, 0 } );
		points.add( new double[] { 100, 100 } );
	}
	hotMap=tileView.addHotSpot(points, startHotSpotEventListener);
	 */

}
